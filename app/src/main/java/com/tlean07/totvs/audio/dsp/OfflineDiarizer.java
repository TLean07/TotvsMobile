package com.tlean07.totvs.audio.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Second-pass speaker clustering over the whole recording, run once the
 * meeting is over. The live labels only ever see the past, so the first
 * sentence of a new voice is misattributed by construction and short answers
 * are guessed from very little audio. Here the complete set of speech frames is
 * available, which allows the classic three-stage pipeline:
 *
 * <ol>
 *   <li><b>Normalisation</b> – cepstral mean/variance normalisation over the
 *       whole recording removes the room and microphone colouring shared by
 *       everybody, so distances become relative to <i>this</i> recording;</li>
 *   <li><b>Segmentation + clustering by BIC</b> – every utterance is cut where
 *       a two-Gaussian model explains the audio better than one (turn changes
 *       without a pause), then pieces are merged bottom-up while the Bayesian
 *       Information Criterion says two pieces are the same voice;</li>
 *   <li><b>Resegmentation</b> – a Viterbi pass re-labels half-second windows
 *       with the final voice models and a cost for switching speaker, cleaning
 *       up the boundaries and the short replies.</li>
 * </ol>
 *
 * Voices that ended up with less than {@value #MIN_SPEAKER_MS} ms of speech are
 * absorbed, speakers are renumbered in order of first appearance and a mapping
 * from the live labels is returned so names typed during the recording follow
 * the right person.
 */
public final class OfflineDiarizer {

    public static final class Result {
        public final List<SpeakerSegment> segments;
        public final int speakers;
        /** liveToFinal[liveSpeaker] = final speaker index (or -1 when that voice never got speech). */
        public final int[] liveToFinal;
        public final List<SpeakerProfile> profiles;

        Result(List<SpeakerSegment> segments, int speakers, int[] liveToFinal, List<SpeakerProfile> profiles) {
            this.segments = segments;
            this.speakers = speakers;
            this.liveToFinal = liveToFinal;
            this.profiles = profiles;
        }
    }

    /** MFCCs (from c1) used by the offline pass; the feature vector is these plus log pitch. */
    public static int MFCC_USED = 12;
    /** Add a loudness dimension: the person holding the phone is louder. */
    public static boolean USE_ENERGY = true;
    static int D = MFCC_USED + 1;
    static int PITCH_DIM = MFCC_USED;
    static int ENERGY_DIM = -1;
    /** Shrinkage of the voice models used for assignment (more than the test's, to limit overfitting). */
    public static double KAPPA_MODEL = 150;
    /** Use the pitch dimension of the vector (else the separate pitch term of the models). */
    public static boolean PITCH_IN_VECTOR = true;
    /**
     * Two pieces are two voices when the likelihood gain exceeds
     * {@code MERGE_A × parameters/2 + MERGE_B × n_eff}: the first term is the
     * gain one voice produces by chance, the second what a genuinely different
     * voice adds per effective frame (same voice ≈ 1.2, other voice ≥ 4 in tests).
     */
    public static double MERGE_A = 1.0;
    public static double MERGE_B = 2.6;
    /** Same test for cutting an utterance in two. */
    public static double SPLIT_A = 1.0;
    public static double SPLIT_B = 2.6;
    /**
     * Below this effective sample size the two-voice test is unreliable (measured:
     * different voices overlap with same-voice pairs), so the bar rises steeply.
     */
    public static double NEFF_SAFE = 90;
    public static double SMALL_PENALTY = 3.5;
    /** Pairs with less effective data than this are never merged during clustering. */
    public static double NEFF_MIN = 40;
    /** Pseudo-frames of the global covariance mixed into every estimate. */
    public static double KAPPA = 20;
    /** Only pitched frames carry the voice; noise, breaths and reverb tails are ignored. */
    public static boolean VOICED_ONLY = true;
    /** ...unless pitch detection found fewer than this share of the speech frames. */
    public static double MIN_VOICED_FRACTION = 0.30;
    /** Smallest piece a cut may produce (~1 s). */
    static final int MIN_SPLIT_FRAMES = 60;
    static final int SPLIT_STEP = 4;
    /** Pieces shorter than this never drive the clustering; they are labelled afterwards. */
    public static int MIN_CLUSTER_FRAMES = 80;
    static final int WINDOW = 32;
    /** Viterbi cost of changing speaker inside a piece / at a piece boundary. */
    public static double SWITCH_PENALTY = 40;
    public static double SWITCH_PENALTY_BOUNDARY = 6;
    static final int MAX_SPEAKERS = 8;
    /** Resegment → re-estimate rounds per candidate speaker count. */
    public static int REFINE_ROUNDS = 3;
    /** Extra seedings tried per speaker count. */
    public static int RESTARTS = 3;
    /** A voice must hold at least this many usable frames (~2.4 s voiced) to count as a person. */
    public static int MIN_VOICE_FRAMES = 150;
    /** Orphan rescue: pieces this short say nothing; a piece is an orphan when its best voice fits it
     *  this much (nats/frame, or this many sigmas) worse than that voice's own pieces. */
    public static int ORPHAN_MIN_FRAMES = 20;
    public static double ORPHAN_MIN_GAP = 1.0;
    public static double ORPHAN_SIGMAS = 2.0;
    static final long MIN_SPEAKER_MS = 1_500;
    /** Two voices whose mean pitch differs by more than this need a much stronger BIC to merge. */
    public static double PITCH_VETO_OCTAVES = 0.45;
    static final int PITCH_VETO_VOICED = 60;
    public static double PITCH_VETO_MARGIN = 150;
    public static double PITCH_SIGMA_FLOOR = 0.12;
    public static double PITCH_WEIGHT = 1.0;

    private final FrameStore frames;
    private final List<SpeakerSegment> live;
    private final int[] from;
    private final int[] to;
    private final int liveSpeakers;
    private float[] x;
    private double[] prior;

    public OfflineDiarizer(FrameStore frames, List<SpeakerSegment> live, int[] from, int[] to, int liveSpeakers) {
        this.frames = frames;
        this.live = live;
        this.from = from;
        this.to = to;
        this.liveSpeakers = liveSpeakers;
    }

    /** Harness only: last instance, for oracle diagnostics. */
    public static OfflineDiarizer last;
    /** Harness only: truth intervals {startMs, endMs, speaker}; when set, every piece pair is dumped. */
    public static List<long[]> oracleTruth;

    private int truthOf(int a, int b) {
        long[] votes = new long[16];
        for (int f = a; f < b; f++) {
            long t = frames.timeMs(f);
            for (long[] tr : oracleTruth) {
                if (t >= tr[0] && t < tr[1]) {
                    votes[(int) tr[2]]++;
                    break;
                }
            }
        }
        int best = -1;
        long bv = 0;
        long tot = 0;
        for (int i = 0; i < votes.length; i++) {
            tot += votes[i];
            if (votes[i] > bv) {
                bv = votes[i];
                best = i;
            }
        }
        return tot > 0 && bv >= 0.8 * tot ? best : -1;
    }

    /**
     * Harness only. {@code truth} = {startMs, endMs, speaker}. Prints the per-frame
     * generalised likelihood-ratio gain between every pair of true speakers and between
     * the two halves of each speaker (same voice), plus n, so thresholds can be judged.
     */
    public String oracle(List<long[]> truth) {
        if (x == null) {
            normalise();
        }
        int k = 0;
        for (long[] t : truth) {
            k = Math.max(k, (int) t[2] + 1);
        }
        GaussianStats[] sp = new GaussianStats[k];
        GaussianStats[][] half = new GaussianStats[k][2];
        int[] count = new int[k];
        for (int i = 0; i < k; i++) {
            sp[i] = new GaussianStats(D);
            half[i][0] = new GaussianStats(D);
            half[i][1] = new GaussianStats(D);
        }
        for (int f = 0; f < frames.size(); f++) {
            if (!usable(f)) {
                continue;
            }
            long t = frames.timeMs(f);
            for (long[] tr : truth) {
                if (t >= tr[0] && t < tr[1]) {
                    int spk = (int) tr[2];
                    sp[spk].add(x, f * D, frames.pitchOctaves(f));
                    half[spk][(count[spk]++ / 40) % 2].add(x, f * D, frames.pitchOctaves(f));
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) {
            sb.append(String.format(Locale.ROOT, "  oracle same spk%d halves gain/frame=%.3f (n=%d,%d) pitch=%.2f%n",
                    i, gainPerFrame(half[i][0], half[i][1]), half[i][0].n, half[i][1].n, sp[i].meanPitch()));
            for (int j = i + 1; j < k; j++) {
                sb.append(String.format(Locale.ROOT, "  oracle diff spk%d-spk%d gain/frame=%.3f (n=%d,%d) dpitch=%.2f%n",
                        i, j, gainPerFrame(sp[i], sp[j]), sp[i].n, sp[j].n, Math.abs(sp[i].meanPitch() - sp[j].meanPitch())));
            }
        }
        return sb.toString();
    }

    private double gainPerFrame(GaussianStats a, GaussianStats b) {
        GaussianStats ab = GaussianStats.merged(a, b);
        double gain = 0.5 * (ab.n * ab.logDet(prior, KAPPA) - a.n * a.logDet(prior, KAPPA) - b.n * b.logDet(prior, KAPPA));
        return gain / Math.max(1, ab.n);
    }

    public Result run() {
        synchronized (OfflineDiarizer.class) {
            return runLocked();
        }
    }

    private Result runLocked() {
        last = this;
        PITCH_DIM = MFCC_USED;
        ENERGY_DIM = USE_ENERGY ? MFCC_USED + 1 : -1;
        D = MFCC_USED + 1 + (USE_ENERGY ? 1 : 0);
        int n = frames.size();
        if (live.isEmpty() || n < 2 * MIN_CLUSTER_FRAMES) {
            return trivial();
        }
        int voiced = 0;
        for (int i = 0; i < n; i++) {
            if (!Float.isNaN(frames.pitchOctaves(i))) {
                voiced++;
            }
        }
        voicedOnly = VOICED_ONLY && voiced >= MIN_VOICED_FRACTION * n;
        normalise();
        // 1. pieces = live utterances cut at BIC change points
        List<int[]> pieces = new ArrayList<>(); // {liveIndex, from, to}
        for (int i = 0; i < live.size(); i++) {
            if (to[i] > from[i]) {
                split(i, from[i], to[i], pieces);
            }
        }
        if (pieces.isEmpty()) {
            return trivial();
        }
        GaussianStats[] pieceStats = new GaussianStats[pieces.size()];
        for (int p = 0; p < pieces.size(); p++) {
            pieceStats[p] = stats(pieces.get(p)[1], pieces.get(p)[2]);
        }
        if (oracleTruth != null && SpeakerDiarizer.debug != null) {
            int[] truth = new int[pieces.size()];
            for (int p = 0; p < pieces.size(); p++) {
                truth[p] = truthOf(pieces.get(p)[1], pieces.get(p)[2]);
            }
            for (int i = 0; i < pieces.size(); i++) {
                for (int j = i + 1; j < pieces.size(); j++) {
                    if (truth[i] < 0 || truth[j] < 0 || pieceStats[i].n < MIN_CLUSTER_FRAMES || pieceStats[j].n < MIN_CLUSTER_FRAMES) {
                        continue;
                    }
                    SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  pair same=%d na=%d nb=%d neff=%.1f gain=%.1f dpitch=%.2f%n",
                            truth[i] == truth[j] ? 1 : 0, pieceStats[i].n, pieceStats[j].n,
                            GaussianStats.effectiveN(pieceStats[i], pieceStats[j]),
                            GaussianStats.gain(pieceStats[i], pieceStats[j], prior, KAPPA),
                            Math.abs(pieceStats[i].meanPitch() - pieceStats[j].meanPitch())));
                }
            }
        }
        // Minimum sizes scale with how much speech there is: a 15 s test with two people
        // has voices of ~100 frames, an hour-long meeting can afford stricter minimums.
        int usableTotal = 0;
        for (GaussianStats g : pieceStats) {
            usableTotal += g.n;
        }
        minVoice = (int) Math.max(40, Math.min(MIN_VOICE_FRAMES, Math.round(0.15 * usableTotal)));
        minCluster = (int) Math.max(40, Math.min(MIN_CLUSTER_FRAMES, Math.round(0.08 * usableTotal)));
        // 2. dendrogram over the pieces long enough to carry a voice
        List<Integer> longPieces = new ArrayList<>();
        for (int p = 0; p < pieces.size(); p++) {
            if (pieceStats[p].n >= minCluster) {
                longPieces.add(p);
            }
        }
        if (longPieces.isEmpty()) {
            for (int p = 0; p < pieces.size(); p++) {
                longPieces.add(p);
            }
        }
        mergeCostAt = new double[longPieces.size() + 1];
        List<int[]> levels = dendrogram(pieceStats, longPieces); // levels.get(K-1)[i] = cluster of longPieces[i] with K clusters
        // 3. K = 1, 2, ...: start from the dendrogram, let every long piece move to the voice that
        //    explains it best, re-estimate, repeat; accept K while every pair of voices passes the
        //    two-voice test on those whole utterances. (Window-level optimisation would make one
        //    voice look like two, so the test is only ever run on natural pieces.)
        int kMax = Math.min(MAX_SPEAKERS, levels.size());
        int bestK = 1;
        int[] bestAssign = levels.get(0);
        double[][] pieceCost = null;
        for (int k = 1; k <= kMax; k++) {
            // candidates: the dendrogram level and a few farthest-first seedings
            List<int[]> inits = new ArrayList<>();
            inits.add(levels.get(k - 1).clone());
            if (k > 1) {
                if (pieceCost == null) {
                    pieceCost = new double[longPieces.size()][longPieces.size()];
                    for (int i = 0; i < longPieces.size(); i++) {
                        for (int j = i + 1; j < longPieces.size(); j++) {
                            double c = GaussianStats.gain(pieceStats[longPieces.get(i)], pieceStats[longPieces.get(j)], prior, KAPPA)
                                    / Math.max(1, GaussianStats.effectiveN(pieceStats[longPieces.get(i)], pieceStats[longPieces.get(j)]));
                            pieceCost[i][j] = c;
                            pieceCost[j][i] = c;
                        }
                    }
                }
                Integer[] byLength = new Integer[longPieces.size()];
                for (int i = 0; i < byLength.length; i++) {
                    byLength[i] = i;
                }
                final List<Integer> lp = longPieces;
                Arrays.sort(byLength, (a, b) -> Integer.compare(pieceStats[lp.get(b)].n, pieceStats[lp.get(a)].n));
                for (int r = 0; r < Math.min(RESTARTS, byLength.length); r++) {
                    inits.add(farthestFirst(pieceCost, byLength[r], k));
                }
            }
            if (k > 1) {
                // the live pass already grouped these utterances; when it heard k voices, try its answer too
                int[] fromLive = new int[longPieces.size()];
                int[] map = new int[Math.max(1, liveSpeakers)];
                Arrays.fill(map, -1);
                int distinct = 0;
                for (int i = 0; i < longPieces.size(); i++) {
                    int sp = live.get(pieces.get(longPieces.get(i))[0]).speaker;
                    sp = Math.max(0, Math.min(sp, map.length - 1));
                    if (map[sp] < 0) {
                        map[sp] = distinct++;
                    }
                    fromLive[i] = map[sp];
                }
                if (distinct == k) {
                    inits.add(fromLive);
                }
            }
            int[] assign = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int[] candidate : inits) {
                double score = refineAssignment(pieces, pieceStats, longPieces, candidate, k);
                if (score > bestScore) {
                    bestScore = score;
                    assign = candidate;
                }
            }
            if (assign == null) {
                break; // no initialisation could hold k voices
            }
            List<GaussianStats> clusters = clustersOf(pieceStats, longPieces, assign, k);
            boolean valid = clusters.size() == k;
            for (GaussianStats g : clusters) {
                if (g.n < minVoice) {
                    valid = false;
                }
            }
            double worst = Double.MAX_VALUE;
            for (int i = 0; valid && i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    double cost = mergeCost(clusters.get(i), clusters.get(j));
                    worst = Math.min(worst, cost);
                    if (cost <= 0) {
                        valid = false;
                    }
                }
            }
            if (SpeakerDiarizer.debug != null) {
                StringBuilder sb = new StringBuilder();
                for (GaussianStats g : clusters) {
                    sb.append(g.n).append(' ');
                }
                SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  K=%d valid=%b worst=%.0f score=%.0f sizes=%s%n", k, valid, worst, bestScore, sb));
                if (oracleTruth != null) {
                    for (int i = 0; i < clusters.size(); i++) {
                        for (int j = i + 1; j < clusters.size(); j++) {
                            SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "     pair %d-%d cost=%.0f neff=%.0f gain=%.0f%n", i, j,
                                    mergeCost(clusters.get(i), clusters.get(j)), GaussianStats.effectiveN(clusters.get(i), clusters.get(j)),
                                    GaussianStats.gain(clusters.get(i), clusters.get(j), prior, KAPPA)));
                        }
                    }
                    long[][] comp = new long[k][8];
                    for (int i = 0; i < longPieces.size(); i++) {
                        int[] piece = pieces.get(longPieces.get(i));
                        for (int f = piece[1]; f < piece[2]; f++) {
                            long t = frames.timeMs(f);
                            for (long[] tr : oracleTruth) {
                                if (t >= tr[0] && t < tr[1]) {
                                    comp[assign[i]][(int) tr[2]]++;
                                    break;
                                }
                            }
                        }
                    }
                    for (int l = 0; l < k; l++) {
                        SpeakerDiarizer.debug.append("     cluster ").append(l).append(" truth=").append(Arrays.toString(comp[l])).append('\n');
                    }
                }
            }
            if (!valid) {
                break;
            }
            bestK = k;
            bestAssign = assign;
        }
        // 4. orphan rescue: short pieces none of the chosen voices explains well may be a person
        //    who only gives short answers. Pool them and try K+1 with that pool as the new voice.
        for (int attempt = 0; attempt < 2; attempt++) {
            int k0 = bestK;
            List<GaussianStats> current = clustersOf(pieceStats, longPieces, bestAssign, k0);
            if (current.size() < k0) {
                break;
            }
            Model[] cur = models(current);
            // typical per-frame fit of each voice on its own pieces
            double[] sum = new double[k0];
            double[] sq = new double[k0];
            int[] cnt = new int[k0];
            double[] fitOf = new double[pieces.size()];
            int[] bestOf = new int[pieces.size()];
            for (int p = 0; p < pieces.size(); p++) {
                double top = Double.NEGATIVE_INFINITY;
                int arg = 0;
                for (int m = 0; m < k0; m++) {
                    double ll = 0;
                    int used = 0;
                    for (int f = pieces.get(p)[1]; f < pieces.get(p)[2]; f++) {
                        if (usable(f)) {
                            ll += cur[m].logLikelihood(x, f * D, frames.pitchOctaves(f));
                            used++;
                        }
                    }
                    double perFrame = used == 0 ? Double.NEGATIVE_INFINITY : ll / used;
                    if (perFrame > top) {
                        top = perFrame;
                        arg = m;
                    }
                }
                fitOf[p] = top;
                bestOf[p] = arg;
            }
            for (int i = 0; i < longPieces.size(); i++) {
                int m = bestAssign[i];
                double v = fitOf[longPieces.get(i)];
                sum[m] += v;
                sq[m] += v * v;
                cnt[m]++;
            }
            List<Integer> orphans = new ArrayList<>();
            int orphanFrames = 0;
            java.util.Set<Integer> isLong = new java.util.HashSet<>(longPieces);
            for (int p = 0; p < pieces.size(); p++) {
                if (isLong.contains(p) || pieceStats[p].n < ORPHAN_MIN_FRAMES) {
                    continue;
                }
                int m = bestOf[p];
                if (cnt[m] < 2) {
                    continue;
                }
                double mean = sum[m] / cnt[m];
                double std = Math.sqrt(Math.max(0, sq[m] / cnt[m] - mean * mean));
                if (fitOf[p] < mean - Math.max(ORPHAN_MIN_GAP, ORPHAN_SIGMAS * std)) {
                    orphans.add(p);
                    orphanFrames += pieceStats[p].n;
                }
            }
            if (SpeakerDiarizer.debug != null) {
                SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  orphans=%d frames=%d%n", orphans.size(), orphanFrames));
            }
            if (orphanFrames < minVoice || k0 >= MAX_SPEAKERS) {
                break;
            }
            List<Integer> extended = new ArrayList<>(longPieces);
            int[] assign = new int[extended.size() + orphans.size()];
            System.arraycopy(bestAssign, 0, assign, 0, bestAssign.length);
            for (int o : orphans) {
                assign[extended.size()] = k0;
                extended.add(o);
            }
            int k1 = k0 + 1;
            refineAssignment(pieces, pieceStats, extended, assign, k1);
            List<GaussianStats> clusters = clustersOf(pieceStats, extended, assign, k1);
            boolean valid = clusters.size() == k1;
            for (GaussianStats g : clusters) {
                if (g.n < minVoice) {
                    valid = false;
                }
            }
            double worst = Double.MAX_VALUE;
            for (int i = 0; valid && i < k1; i++) {
                for (int j = i + 1; j < k1; j++) {
                    double cost = mergeCost(clusters.get(i), clusters.get(j));
                    worst = Math.min(worst, cost);
                    if (cost <= 0) {
                        valid = false;
                    }
                }
            }
            if (SpeakerDiarizer.debug != null) {
                SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  rescue K=%d valid=%b worst=%.0f%n", k1, valid, worst));
            }
            if (!valid) {
                break;
            }
            longPieces = extended;
            bestAssign = assign;
            bestK = k1;
        }
        int k = bestK;
        List<GaussianStats> clusters = clustersOf(pieceStats, longPieces, bestAssign, k);
        Model[] models = models(clusters);
        int[] label = resegment(pieces, models);
        for (int round = 0; round < REFINE_ROUNDS; round++) {
            clusters = statsFromLabels(label, k);
            if (clusters.size() < k) {
                break;
            }
            models = models(clusters);
            int[] next = resegment(pieces, models);
            boolean same = Arrays.equals(next, label);
            label = next;
            if (same) {
                break;
            }
        }
        return build(pieces, label, k);
    }

    /** Seeds: `first`, then repeatedly the piece farthest (largest min cost) from the seeds; assigns by nearest seed. */
    private static int[] farthestFirst(double[][] cost, int first, int k) {
        int n = cost.length;
        int[] seeds = new int[k];
        seeds[0] = first;
        double[] minCost = new double[n];
        for (int i = 0; i < n; i++) {
            minCost[i] = cost[i][first];
        }
        for (int s = 1; s < k; s++) {
            int far = -1;
            double top = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (minCost[i] > top) {
                    top = minCost[i];
                    far = i;
                }
            }
            seeds[s] = far;
            for (int i = 0; i < n; i++) {
                minCost[i] = Math.min(minCost[i], cost[i][far]);
            }
        }
        int[] assign = new int[n];
        for (int i = 0; i < n; i++) {
            int best = 0;
            double low = Double.MAX_VALUE;
            for (int s = 0; s < k; s++) {
                double c = i == seeds[s] ? -1 : cost[i][seeds[s]];
                if (c < low) {
                    low = c;
                    best = s;
                }
            }
            assign[i] = best;
        }
        return assign;
    }

    /** Iterates "assign every long piece to its best voice / re-estimate"; returns the final total log-likelihood. */
    private double refineAssignment(List<int[]> pieces, GaussianStats[] pieceStats, List<Integer> longPieces, int[] assign, int k) {
        double score = Double.NEGATIVE_INFINITY;
        for (int round = 0; round < 8; round++) {
            List<GaussianStats> clusters = clustersOf(pieceStats, longPieces, assign, k);
            if (clusters.size() < k) {
                return Double.NEGATIVE_INFINITY;
            }
            Model[] models = models(clusters);
            boolean changed = false;
            score = 0;
            for (int i = 0; i < longPieces.size(); i++) {
                int[] piece = pieces.get(longPieces.get(i));
                int best = -1;
                double top = Double.NEGATIVE_INFINITY;
                for (int m = 0; m < k; m++) {
                    double ll = 0;
                    for (int f = piece[1]; f < piece[2]; f++) {
                        if (usable(f)) {
                            ll += models[m].logLikelihood(x, f * D, frames.pitchOctaves(f));
                        }
                    }
                    if (ll > top) {
                        top = ll;
                        best = m;
                    }
                }
                score += top;
                if (best != assign[i]) {
                    assign[i] = best;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return score;
    }

    private static List<GaussianStats> clustersOf(GaussianStats[] pieceStats, List<Integer> longPieces, int[] assign, int k) {
        GaussianStats[] acc = new GaussianStats[k];
        for (int c = 0; c < k; c++) {
            acc[c] = new GaussianStats(D);
        }
        for (int i = 0; i < longPieces.size(); i++) {
            acc[assign[i]].addAll(pieceStats[longPieces.get(i)]);
        }
        List<GaussianStats> out = new ArrayList<>();
        for (GaussianStats g : acc) {
            if (g.n > 0) {
                out.add(g);
            }
        }
        return out;
    }

    private int minVoice = MIN_VOICE_FRAMES;
    private int minCluster = MIN_CLUSTER_FRAMES;

    /** mergeCostAt[alive] = cost of the merge that took the dendrogram from `alive` to `alive-1` clusters. */
    private double[] mergeCostAt;

    /**
     * Greedy bottom-up merging of the given pieces, lowest merge cost first, all
     * the way to one cluster. Returns, for every K, the cluster index of each piece.
     */
    private List<int[]> dendrogram(GaussianStats[] pieceStats, List<Integer> pieces) {
        int c = pieces.size();
        List<GaussianStats> clusters = new ArrayList<>();
        int[] assign = new int[c];
        for (int i = 0; i < c; i++) {
            clusters.add(pieceStats[pieces.get(i)].copy());
            assign[i] = i;
        }
        double[][] cost = new double[c][c];
        for (int i = 0; i < c; i++) {
            for (int j = i + 1; j < c; j++) {
                cost[i][j] = mergeCost(clusters.get(i), clusters.get(j));
            }
        }
        boolean[] dead = new boolean[c];
        List<int[]> levels = new ArrayList<>();
        int alive = c;
        while (true) {
            // record the level with `alive` clusters, compacted to 0..alive-1
            int[] compact = new int[c];
            int[] map = new int[c];
            Arrays.fill(map, -1);
            int next = 0;
            for (int i = 0; i < c; i++) {
                int root = assign[i];
                if (map[root] < 0) {
                    map[root] = next++;
                }
                compact[i] = map[root];
            }
            while (levels.size() < alive) {
                levels.add(null);
            }
            levels.set(alive - 1, compact);
            if (alive == 1) {
                break;
            }
            int bi = -1;
            int bj = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < c; i++) {
                if (dead[i]) {
                    continue;
                }
                for (int j = i + 1; j < c; j++) {
                    if (!dead[j] && cost[i][j] < best) {
                        best = cost[i][j];
                        bi = i;
                        bj = j;
                    }
                }
            }
            if (SpeakerDiarizer.debug != null) {
                SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  merge %d+%d cost=%.0f (n=%d,%d)%n",
                        bi, bj, best, clusters.get(bi).n, clusters.get(bj).n));
            }
            mergeCostAt[alive] = best;
            clusters.get(bi).addAll(clusters.get(bj));
            dead[bj] = true;
            for (int i = 0; i < c; i++) {
                if (assign[i] == bj) {
                    assign[i] = bi;
                }
            }
            alive--;
            for (int k = 0; k < c; k++) {
                if (!dead[k] && k != bi) {
                    cost[Math.min(bi, k)][Math.max(bi, k)] = mergeCost(clusters.get(bi), clusters.get(k));
                }
            }
        }
        return levels;
    }

    /** Cluster statistics rebuilt from the window labels of the last resegmentation. */
    private List<GaussianStats> statsFromLabels(int[] label, int k) {
        GaussianStats[] acc = new GaussianStats[k];
        for (int m = 0; m < k; m++) {
            acc[m] = new GaussianStats(D);
        }
        for (int i = 0; i < windows.size(); i++) {
            int l = label[i];
            if (l < 0) {
                continue;
            }
            int[] win = windows.get(i);
            for (int f = win[1]; f < win[2]; f++) {
                if (usable(f)) {
                    acc[l].add(x, f * D, frames.pitchOctaves(f));
                }
            }
        }
        List<GaussianStats> out = new ArrayList<>();
        for (GaussianStats g : acc) {
            if (g.n > 0) {
                out.add(g);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ steps

    private void normalise() {
        int n = frames.size();
        double[] mean = new double[D];
        double[] var = new double[D];
        int voiced = 0;
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < PITCH_DIM; d++) {
                mean[d] += frames.mfcc(i, d);
            }
            float p = frames.pitchOctaves(i);
            if (!Float.isNaN(p)) {
                mean[PITCH_DIM] += p;
                voiced++;
            }
            if (ENERGY_DIM >= 0) {
                mean[ENERGY_DIM] += frames.energy(i);
            }
        }
        if (ENERGY_DIM >= 0) {
            mean[ENERGY_DIM] /= n;
        }
        for (int d = 0; d < PITCH_DIM; d++) {
            mean[d] /= n;
        }
        mean[PITCH_DIM] /= Math.max(1, voiced);
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < PITCH_DIM; d++) {
                double v = frames.mfcc(i, d) - mean[d];
                var[d] += v * v;
            }
            float p = frames.pitchOctaves(i);
            if (!Float.isNaN(p)) {
                double v = p - mean[PITCH_DIM];
                var[PITCH_DIM] += v * v;
            }
            if (ENERGY_DIM >= 0) {
                double v = frames.energy(i) - mean[ENERGY_DIM];
                var[ENERGY_DIM] += v * v;
            }
        }
        if (ENERGY_DIM >= 0) {
            var[ENERGY_DIM] = Math.sqrt(var[ENERGY_DIM] / n) + 1e-6;
        }
        for (int d = 0; d < PITCH_DIM; d++) {
            var[d] = Math.sqrt(var[d] / n) + 1e-6;
        }
        var[PITCH_DIM] = Math.sqrt(var[PITCH_DIM] / Math.max(1, voiced)) + 1e-6;
        x = new float[n * D];
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < PITCH_DIM; d++) {
                x[i * D + d] = (float) ((frames.mfcc(i, d) - mean[d]) / var[d]);
            }
            float p = frames.pitchOctaves(i);
            x[i * D + PITCH_DIM] = Float.isNaN(p) || !PITCH_IN_VECTOR ? 0f : (float) ((p - mean[PITCH_DIM]) / var[PITCH_DIM]);
            if (ENERGY_DIM >= 0) {
                x[i * D + ENERGY_DIM] = (float) ((frames.energy(i) - mean[ENERGY_DIM]) / var[ENERGY_DIM]);
            }
        }
        prior = new double[D * D];
        for (int d = 0; d < D; d++) {
            prior[d * D + d] = 1.0;
        }
    }

    /** Set in {@link #runLocked}: with little pitched speech (reverberant room, far talkers) every speech frame counts. */
    private boolean voicedOnly = VOICED_ONLY;

    private boolean usable(int frame) {
        return !voicedOnly || !Float.isNaN(frames.pitchOctaves(frame));
    }

    private GaussianStats stats(int a, int b) {
        GaussianStats s = new GaussianStats(D);
        for (int i = a; i < b; i++) {
            if (usable(i)) {
                s.add(x, i * D, frames.pitchOctaves(i));
            }
        }
        return s;
    }

    private void remove(GaussianStats s, int i) {
        s.n--;
        for (int d = 0; d < D; d++) {
            double xi = x[i * D + d];
            s.sum[d] -= xi;
            for (int j = 0; j <= d; j++) {
                s.sq[d * D + j] -= xi * x[i * D + j];
            }
        }
        float p = frames.pitchOctaves(i);
        if (!Float.isNaN(p)) {
            s.voiced--;
            s.pitchSum -= p;
            s.pitchSq -= p * (double) p;
        }
    }

    /** Recursively cuts [a,b) where two Gaussians beat one by the BIC. */
    private void split(int liveIndex, int a, int b, List<int[]> out) {
        GaussianStats all = stats(a, b);
        int n = all.n;
        if (n < 2 * MIN_SPLIT_FRAMES) {
            out.add(new int[] {liveIndex, a, b});
            return;
        }
        // head grows frame by frame, tail shrinks; both keep only usable frames
        GaussianStats head = new GaussianStats(D);
        GaussianStats tail = all.copy();
        double allLd = all.logDet(prior, KAPPA);
        int bestCut = -1;
        double best = 0;
        int sinceEval = 0;
        for (int c = a; c < b; c++) {
            if (usable(c)) {
                head.add(x, c * D, frames.pitchOctaves(c));
                remove(tail, c);
                sinceEval++;
            }
            if (head.n < MIN_SPLIT_FRAMES || tail.n < MIN_SPLIT_FRAMES || sinceEval < SPLIT_STEP) {
                continue;
            }
            sinceEval = 0;
            double gain = 0.5 * (n * allLd - head.n * head.logDet(prior, KAPPA) - tail.n * tail.logDet(prior, KAPPA));
            double delta = gain - threshold(SPLIT_A, SPLIT_B, GaussianStats.effectiveN(head, tail));
            if (delta > best) {
                best = delta;
                bestCut = c + 1;
            }
        }
        if (bestCut < 0) {
            if (SpeakerDiarizer.debug != null) {
                SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  nosplit live=%d n=%d%n", liveIndex, n));
            }
            out.add(new int[] {liveIndex, a, b});
            return;
        }
        if (SpeakerDiarizer.debug != null) {
            SpeakerDiarizer.debug.append(String.format(Locale.ROOT, "  split live=%d at %d ms dBIC=%.0f%n",
                    liveIndex, frames.timeMs(bestCut), best));
        }
        split(liveIndex, a, bestCut, out);
        split(liveIndex, bestCut, b, out);
    }

    /** Gain a genuinely different voice must exceed, given the effective sample size. */
    private static double threshold(double a, double b, double neff) {
        return a * 0.5 * GaussianStats.parameters(D) + b * neff + SMALL_PENALTY * Math.max(0, NEFF_SAFE - neff);
    }

    /** Negative = same voice (merge). */
    private double mergeCost(GaussianStats a, GaussianStats b) {
        double neff = GaussianStats.effectiveN(a, b);
        if (neff < NEFF_MIN) {
            return Double.MAX_VALUE / 4;
        }
        double d = GaussianStats.gain(a, b, prior, KAPPA) - threshold(MERGE_A, MERGE_B, neff);
        if (a.voiced >= PITCH_VETO_VOICED && b.voiced >= PITCH_VETO_VOICED
                && Math.abs(a.meanPitch() - b.meanPitch()) > PITCH_VETO_OCTAVES) {
            d += PITCH_VETO_MARGIN;
        }
        return d;
    }

    private static final class Model {
        final double[] mean = new double[D];
        double[] chol;
        double logDet;
        double pitchMean;
        double pitchSigma;
        boolean hasPitch;
        final double[] scratch = new double[D];

        double logLikelihood(float[] x, int off, float pitch) {
            for (int i = 0; i < D; i++) {
                double s = x[off + i] - mean[i];
                for (int k = 0; k < i; k++) {
                    s -= chol[i * D + k] * scratch[k];
                }
                scratch[i] = s / chol[i * D + i];
            }
            double mahal = 0;
            for (int i = 0; i < D; i++) {
                mahal += scratch[i] * scratch[i];
            }
            double ll = -0.5 * (logDet + mahal);
            if (hasPitch && !Float.isNaN(pitch)) {
                double z = (pitch - pitchMean) / pitchSigma;
                ll += PITCH_WEIGHT * (-0.5 * z * z - Math.log(pitchSigma));
            }
            return ll;
        }
    }

    private Model[] models(List<GaussianStats> clusters) {
        Model[] out = new Model[clusters.size()];
        for (int k = 0; k < out.length; k++) {
            GaussianStats s = clusters.get(k);
            Model m = new Model();
            for (int d = 0; d < D; d++) {
                m.mean[d] = s.mean(d);
            }
            m.chol = s.cholesky(prior, KAPPA_MODEL);
            if (m.chol == null) {
                m.chol = new GaussianStats(D).cholesky(prior, 1);
            }
            m.logDet = 0;
            for (int d = 0; d < D; d++) {
                m.logDet += 2 * Math.log(m.chol[d * D + d]);
            }
            m.hasPitch = !PITCH_IN_VECTOR && s.voiced >= 10;
            if (m.hasPitch) {
                m.pitchMean = s.meanPitch();
                m.pitchSigma = Math.max(PITCH_SIGMA_FLOOR, s.pitchStd());
            }
            out[k] = m;
        }
        return out;
    }

    /** Window = {pieceIndex, from, to}; returns the label of every window after Viterbi and absorption. */
    private List<int[]> windows;

    private int[] resegment(List<int[]> pieces, Model[] models) {
        windows = new ArrayList<>();
        for (int p = 0; p < pieces.size(); p++) {
            int a = pieces.get(p)[1];
            int b = pieces.get(p)[2];
            if (b <= a) {
                windows.add(new int[] {p, a, b});
                continue;
            }
            for (int w = a; w < b; w += WINDOW) {
                int end = Math.min(b, w + WINDOW);
                if (b - end < WINDOW / 2) {
                    end = b;
                }
                windows.add(new int[] {p, w, end});
                if (end == b) {
                    break;
                }
            }
        }
        int k = models.length;
        int w = windows.size();
        double[][] score = new double[w][k];
        for (int i = 0; i < w; i++) {
            int[] win = windows.get(i);
            for (int m = 0; m < k; m++) {
                double s = 0;
                for (int f = win[1]; f < win[2]; f++) {
                    if (usable(f)) {
                        s += models[m].logLikelihood(x, f * D, frames.pitchOctaves(f));
                    }
                }
                score[i][m] = s;
            }
        }
        boolean[] enabled = new boolean[k];
        Arrays.fill(enabled, true);
        int[] label;
        while (true) {
            label = viterbi(pieces, score, enabled);
            long[] ms = new long[k];
            for (int i = 0; i < w; i++) {
                int[] win = windows.get(i);
                if (label[i] >= 0) {
                    ms[label[i]] += (win[2] - win[1]) * frameMs();
                }
            }
            int weakest = -1;
            long weakestMs = Long.MAX_VALUE;
            int alive = 0;
            for (int m = 0; m < k; m++) {
                if (enabled[m]) {
                    alive++;
                    if (ms[m] < weakestMs) {
                        weakestMs = ms[m];
                        weakest = m;
                    }
                }
            }
            if (alive <= 1 || weakestMs >= MIN_SPEAKER_MS) {
                break;
            }
            enabled[weakest] = false;
        }
        return label;
    }

    private int[] viterbi(List<int[]> pieces, double[][] score, boolean[] enabled) {
        int w = windows.size();
        int k = enabled.length;
        double[][] best = new double[w][k];
        int[][] back = new int[w][k];
        for (int m = 0; m < k; m++) {
            best[0][m] = enabled[m] ? score[0][m] : Double.NEGATIVE_INFINITY;
        }
        for (int i = 1; i < w; i++) {
            int[] prev = windows.get(i - 1);
            int[] cur = windows.get(i);
            double penalty;
            if (prev[0] == cur[0]) {
                penalty = SWITCH_PENALTY;
            } else {
                penalty = SWITCH_PENALTY_BOUNDARY;
            }
            for (int m = 0; m < k; m++) {
                if (!enabled[m]) {
                    best[i][m] = Double.NEGATIVE_INFINITY;
                    continue;
                }
                double top = Double.NEGATIVE_INFINITY;
                int arg = -1;
                for (int p = 0; p < k; p++) {
                    double v = best[i - 1][p] - (p == m ? 0 : penalty);
                    if (v > top) {
                        top = v;
                        arg = p;
                    }
                }
                best[i][m] = top + score[i][m];
                back[i][m] = arg;
            }
        }
        int[] label = new int[w];
        int arg = -1;
        double top = Double.NEGATIVE_INFINITY;
        for (int m = 0; m < k; m++) {
            if (best[w - 1][m] > top) {
                top = best[w - 1][m];
                arg = m;
            }
        }
        for (int i = w - 1; i >= 0; i--) {
            label[i] = arg;
            arg = back[i][arg];
        }
        return label;
    }

    private Result build(List<int[]> pieces, int[] label, int k) {
        // per live segment: runs of equal labels over its windows
        List<SpeakerSegment> out = new ArrayList<>();
        int[] renumber = new int[k];
        Arrays.fill(renumber, -1);
        int next = 0;
        long[][] overlap = new long[Math.max(1, liveSpeakers)][k];
        int lastLabel = 0;
        int wi = 0;
        for (int li = 0; li < live.size(); li++) {
            SpeakerSegment seg = live.get(li);
            List<int[]> runs = new ArrayList<>(); // {label, startMs}
            while (wi < windows.size() && pieces.get(windows.get(wi)[0])[0] == li) {
                int[] win = windows.get(wi);
                int l = label[wi] >= 0 ? label[wi] : lastLabel;
                if (win[2] > win[1]) {
                    long start = runs.isEmpty() ? seg.startMs : frames.timeMs(win[1]);
                    if (runs.isEmpty() || runs.get(runs.size() - 1)[0] != l) {
                        runs.add(new int[] {l, (int) start});
                    }
                    lastLabel = l;
                }
                wi++;
            }
            if (runs.isEmpty()) {
                runs.add(new int[] {lastLabel, (int) seg.startMs});
            }
            for (int r = 0; r < runs.size(); r++) {
                int l = runs.get(r)[0];
                long start = r == 0 ? seg.startMs : runs.get(r)[1];
                long end = r + 1 < runs.size() ? runs.get(r + 1)[1] : seg.endMs;
                if (end <= start) {
                    continue;
                }
                if (renumber[l] < 0) {
                    renumber[l] = next++;
                }
                out.add(new SpeakerSegment(start, end, renumber[l], false, 0f));
                if (seg.speaker >= 0 && seg.speaker < overlap.length) {
                    overlap[seg.speaker][l] += end - start;
                }
            }
        }
        int[] liveToFinal = new int[Math.max(1, liveSpeakers)];
        for (int s = 0; s < liveToFinal.length; s++) {
            int best = -1;
            long bestMs = 0;
            for (int l = 0; l < k; l++) {
                if (overlap[s][l] > bestMs) {
                    bestMs = overlap[s][l];
                    best = l;
                }
            }
            liveToFinal[s] = best < 0 ? -1 : renumber[best];
        }
        List<SpeakerProfile> profiles = new ArrayList<>();
        for (int s = 0; s < next; s++) {
            profiles.add(new SpeakerProfile());
        }
        for (int i = 0; i < windows.size(); i++) {
            int[] win = windows.get(i);
            int l = label[i] >= 0 ? renumber[label[i]] : -1;
            if (l < 0) {
                continue;
            }
            for (int f = win[1]; f < win[2]; f++) {
                frames.addTo(profiles.get(l), f);
            }
        }
        return new Result(out, next, liveToFinal, profiles);
    }

    /** Too little audio for statistics: keep the live labels, renumbered by first appearance. */
    private Result trivial() {
        int[] renumber = new int[Math.max(1, liveSpeakers)];
        Arrays.fill(renumber, -1);
        int next = 0;
        List<SpeakerSegment> out = new ArrayList<>();
        for (SpeakerSegment s : live) {
            int sp = Math.max(0, Math.min(s.speaker, renumber.length - 1));
            if (renumber[sp] < 0) {
                renumber[sp] = next++;
            }
            out.add(new SpeakerSegment(s.startMs, s.endMs, renumber[sp], false, s.distance));
        }
        List<SpeakerProfile> profiles = new ArrayList<>();
        for (int s = 0; s < next; s++) {
            profiles.add(new SpeakerProfile());
        }
        for (int i = 0; i < live.size(); i++) {
            int l = out.get(i).speaker;
            for (int f = from[i]; f < to[i]; f++) {
                frames.addTo(profiles.get(l), f);
            }
        }
        return new Result(out, next, renumber, profiles);
    }

    private static long frameMs() {
        return FeatureExtractor.HOP * 1000L / FeatureExtractor.SAMPLE_RATE;
    }
}
