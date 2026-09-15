package com.tlean07.totvs.audio.dsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Streaming "who is talking" detector. Feed it 16 kHz mono PCM16 and it reports
 * speech segments labelled with a speaker index, entirely on the device:
 *
 * <ol>
 *   <li>{@link FeatureExtractor} turns the audio into 16 ms frames (energy, MFCC, pitch);</li>
 *   <li>{@link VoiceActivityDetector} cuts the stream into utterances at pauses;</li>
 *   <li>every utterance gets a {@link SpeakerProfile} (timbre + pitch fingerprint);</li>
 *   <li>{@link SpeakerClusterer} matches it against the voices heard so far or
 *       creates a new speaker when the voice is clearly different.</li>
 * </ol>
 *
 * While an utterance is still running the diarizer also emits a provisional label
 * (so the UI can show "Pessoa 2 falando" almost immediately) and watches for a
 * voice change inside the utterance – two people talking back-to-back with no
 * pause – splitting it when the last second sounds like somebody else.
 */
public final class SpeakerDiarizer implements FeatureExtractor.Listener {

    public interface Listener {
        /** Speech started at {@code tMs}. */
        void onSpeechStart(long tMs);

        /** Current guess for the open utterance (speaker may equal speakerCount() = "new person"). */
        void onProvisional(SpeakerSegment segment);

        /** An utterance was closed and attributed. {@code created} = a new speaker was founded. */
        void onSegment(SpeakerSegment segment, boolean created);

        /** Speech stopped at {@code tMs}. */
        void onSpeechEnd(long tMs);
    }

    /** Speaker index used in a provisional segment while the voice is still unidentified. */
    public static final int UNKNOWN = -1;
    /** Pause length that ends an utterance. */
    public static final int MIN_GAP_MS = 300;
    /** Utterances shorter than this are noise (door, cough) and never labelled. */
    private static final int MIN_UTTERANCE_MS = 250;
    /** Force a decision on long monologues so the transcript keeps flowing. */
    private static final int MAX_UTTERANCE_MS = 12_000;
    private static final int PROVISIONAL_EVERY_MS = 500;
    private static final int PROVISIONAL_MIN_VOICED = 30;
    /** A voice change is searched for once the open utterance is at least this long. */
    private static final int SPLIT_MIN_SEGMENT_MS = 2_000;
    /** The change point is searched within this window before "now"... */
    private static final int SPLIT_SEARCH_MS = 2_000;
    /** ...but never closer to "now" than this, so the tail has some evidence. */
    private static final int SPLIT_MIN_TAIL_MS = 400;
    private static final int SPLIT_STEP_FRAMES = 6;
    private static final int SPLIT_HEAD_MIN_VOICED = 25;
    private static final int SPLIT_TAIL_MIN_VOICED = 40;
    /** Novelty split: head and tail differ more (×) than a new-speaker decision would need. */
    private static final float SPLIT_NOVELTY_FACTOR = 1.35f;
    /** Classification split: tail matches another known voice and is much closer to it than to the head. */
    private static final float SPLIT_OTHER_RATIO = 0.75f;
    private static final float SPLIT_OTHER_MIN_FACTOR = 1.0f;

    /** Optional trace sink for tuning (harness only). */
    public static StringBuilder debug;

    private final FeatureExtractor extractor = new FeatureExtractor(this);
    private final VoiceActivityDetector vad = new VoiceActivityDetector(MIN_GAP_MS);
    private final SpeakerClusterer clusterer;
    private final Listener listener;
    private final List<SpeakerSegment> segments = new ArrayList<>();
    /** Voice fingerprint of every committed segment, parallel to {@link #segments}. */
    private final List<SpeakerProfile> segmentProfiles = new ArrayList<>();
    /** Every speech frame of the recording plus, per committed segment, its frame range. */
    private final FrameStore frames = new FrameStore();
    private final List<int[]> segmentFrames = new ArrayList<>();
    private int speakerCountAfterRefine = -1;
    private OfflineDiarizer.Result refined;

    private final List<FrameFeatures> current = new ArrayList<>();
    private boolean open;
    private long segmentStartMs;
    private long lastLoudMs;
    private long lastProvisionalMs;
    private int provisionalSpeaker = -1;

    public SpeakerDiarizer(Listener listener) {
        this(listener, new SpeakerClusterer());
    }

    public SpeakerDiarizer(Listener listener, SpeakerClusterer clusterer) {
        this.listener = listener;
        this.clusterer = clusterer;
    }

    public void process(short[] pcm, int length) {
        extractor.process(pcm, length);
    }

    /** Ends the stream (or a pause): flushes buffered audio and closes the open utterance. */
    public void flush() {
        extractor.finish();
        if (open) {
            closeSegment(lastLoudMs + frameMs());
        }
        vad.reset();
    }

    public long positionMs() {
        return extractor.positionMs();
    }

    public int speakerCount() {
        return speakerCountAfterRefine >= 0 ? speakerCountAfterRefine : clusterer.speakerCount();
    }

    public List<SpeakerSegment> segments() {
        return Collections.unmodifiableList(segments);
    }

    public boolean isSpeaking() {
        return open;
    }

    public float noiseFloorDb() {
        return vad.noiseFloorDb();
    }

    /** The speaker the open utterance is currently attributed to, or -1 when nobody speaks. */
    public int currentSpeaker() {
        return open ? provisionalSpeaker : -1;
    }

    @Override
    public void onFrame(FrameFeatures f) {
        boolean speech = vad.update(f.energyDb);
        if (speech) {
            if (!open) {
                open = true;
                current.clear();
                provisionalSpeaker = -1;
                segmentStartMs = Math.max(0, f.timeMs - 2L * frameMs());
                lastProvisionalMs = segmentStartMs;
                listener.onSpeechStart(segmentStartMs);
            }
            if (f.energyDb > vad.thresholdDb() - 3f) {
                current.add(f.copy());
                lastLoudMs = f.timeMs;
            }
            long elapsed = f.timeMs - segmentStartMs;
            if (elapsed >= MAX_UTTERANCE_MS) {
                closeSegment(f.timeMs + frameMs());
                open = true;
                current.clear();
                provisionalSpeaker = -1;
                segmentStartMs = f.timeMs + frameMs();
                lastProvisionalMs = segmentStartMs;
                listener.onSpeechStart(segmentStartMs);
            } else if (f.timeMs - lastProvisionalMs >= PROVISIONAL_EVERY_MS) {
                lastProvisionalMs = f.timeMs;
                if (!trySplit(f.timeMs)) {
                    provisional(f.timeMs + frameMs());
                }
            }
        } else if (open) {
            closeSegment(lastLoudMs + frameMs());
        }
    }

    private void provisional(long nowMs) {
        SpeakerProfile profile = profileOf(current, 0, current.size());
        if (profile.voicedFrames() < PROVISIONAL_MIN_VOICED && clusterer.speakerCount() > 0) {
            // Too early to judge: tell the UI somebody is talking, identity unknown.
            if (provisionalSpeaker < 0) {
                listener.onProvisional(new SpeakerSegment(segmentStartMs, nowMs, UNKNOWN, true));
            }
            return;
        }
        SpeakerClusterer.Decision d = clusterer.peek(profile);
        if (d.speaker != provisionalSpeaker) {
            provisionalSpeaker = d.speaker;
            listener.onProvisional(new SpeakerSegment(segmentStartMs, nowMs, d.speaker, true, d.distance));
        }
    }

    /**
     * Looks for a voice change inside the open utterance (two people talking
     * back-to-back without a pause). Every candidate cut in the last
     * {@value #SPLIT_SEARCH_MS} ms is scored by how different the audio before
     * and after it sounds; the best cut wins when the two halves differ as much
     * as two speakers would, or when the tail is clearly closer to another voice
     * already known than to the head. The head is then committed and a new
     * utterance starts at the boundary.
     */
    private boolean trySplit(long nowMs) {
        if (nowMs - segmentStartMs < SPLIT_MIN_SEGMENT_MS || current.size() < 2 * SPLIT_STEP_FRAMES) {
            return false;
        }
        int lastCut = current.size();
        while (lastCut > 0 && current.get(lastCut - 1).timeMs > nowMs - SPLIT_MIN_TAIL_MS) {
            lastCut--;
        }
        int firstCut = lastCut;
        while (firstCut > 0 && current.get(firstCut - 1).timeMs > nowMs - SPLIT_SEARCH_MS) {
            firstCut--;
        }
        firstCut = Math.max(firstCut, 1);
        int bestCut = -1;
        float bestScore = -1f;
        float bestDistance = 0f;
        float bestBase = 1f;
        SpeakerProfile bestHead = null;
        SpeakerProfile bestTail = null;
        for (int cut = firstCut; cut < lastCut; cut += SPLIT_STEP_FRAMES) {
            SpeakerProfile head = profileOf(current, 0, cut);
            SpeakerProfile tail = profileOf(current, cut, current.size());
            if (head.voicedFrames() < SPLIT_HEAD_MIN_VOICED || tail.voicedFrames() < SPLIT_TAIL_MIN_VOICED) {
                continue;
            }
            float d = head.distanceTo(tail);
            // Normalise by the short-window inflation so a noisy 0.4 s tail can't
            // out-score a clean 1.5 s one just by being short.
            float base = clusterer.thresholdFor(Math.min(head.voicedFrames(), tail.voicedFrames()));
            float score = d / base;
            if (debug != null) {
                debug.append(String.format(java.util.Locale.ROOT, "    cand now=%d cut=%d head=%d tail=%d d=%.2f z=%.2f%n",
                        nowMs, current.get(cut).timeMs, head.voicedFrames(), tail.voicedFrames(), d, score));
            }
            if (score > bestScore) {
                bestScore = score;
                bestDistance = d;
                bestBase = base;
                bestCut = cut;
                bestHead = head;
                bestTail = tail;
            }
        }
        if (bestCut < 0) {
            return false;
        }
        boolean novelty = bestScore > SPLIT_NOVELTY_FACTOR;
        boolean classified = false;
        float dOther = Float.NaN;
        if (!novelty && clusterer.speakerCount() > 1) {
            int headSpeaker = clusterer.peek(bestHead).speaker;
            float dCurrent = bestDistance;
            if (headSpeaker < clusterer.speakerCount()) {
                dCurrent = Math.min(dCurrent, bestTail.distanceTo(clusterer.centroid(headSpeaker)));
            }
            dOther = Float.MAX_VALUE;
            for (int i = 0; i < clusterer.speakerCount(); i++) {
                if (i != headSpeaker) {
                    dOther = Math.min(dOther, bestTail.distanceTo(clusterer.centroid(i)));
                }
            }
            classified = dOther < bestBase * SPLIT_OTHER_MIN_FACTOR && dOther < dCurrent * SPLIT_OTHER_RATIO;
        }
        if (debug != null) {
            debug.append(String.format(java.util.Locale.ROOT,
                    "    best cut=%d d=%.2f z=%.2f dOther=%.2f novelty=%b classified=%b%n",
                    current.get(bestCut).timeMs, bestDistance, bestScore, dOther, novelty, classified));
        }
        if (!novelty && !classified) {
            return false;
        }
        int cut = bestCut;
        long boundary = current.get(cut).timeMs;
        List<FrameFeatures> tailFrames = new ArrayList<>(current.subList(cut, current.size()));
        List<FrameFeatures> headFrames = new ArrayList<>(current.subList(0, cut));
        commit(headFrames, segmentStartMs, boundary);
        current.clear();
        current.addAll(tailFrames);
        segmentStartMs = boundary;
        provisionalSpeaker = -1;
        listener.onSpeechStart(boundary);
        provisional(nowMs + frameMs());
        return true;
    }

    private void closeSegment(long endMs) {
        open = false;
        List<FrameFeatures> frames = new ArrayList<>(current);
        current.clear();
        commit(frames, segmentStartMs, Math.max(endMs, segmentStartMs + frameMs()));
        provisionalSpeaker = -1;
        listener.onSpeechEnd(endMs);
    }

    private void commit(List<FrameFeatures> frames, long startMs, long endMs) {
        if (endMs - startMs < MIN_UTTERANCE_MS || frames.isEmpty()) {
            return;
        }
        SpeakerProfile profile = profileOf(frames, 0, frames.size());
        SpeakerClusterer.Decision d = clusterer.assign(profile);
        SpeakerSegment segment = new SpeakerSegment(startMs, endMs, d.speaker, false, d.distance);
        segments.add(segment);
        segmentProfiles.add(profile);
        int first = this.frames.size();
        for (FrameFeatures f : frames) {
            this.frames.add(f);
        }
        segmentFrames.add(new int[] {first, this.frames.size()});
        listener.onSegment(segment, d.created);
    }

    /**
     * Second pass once the recording is over: re-clusters every speech frame
     * with {@link OfflineDiarizer} (normalisation, BIC segmentation/clustering,
     * Viterbi resegmentation), absorbs negligible voices and renumbers speakers
     * in order of first appearance. Returns the relabelled segments (also kept
     * internally).
     */
    public List<SpeakerSegment> refine() {
        if (segments.isEmpty()) {
            return segments();
        }
        int[] from = new int[segments.size()];
        int[] to = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            from[i] = segmentFrames.get(i)[0];
            to[i] = segmentFrames.get(i)[1];
        }
        refined = new OfflineDiarizer(frames, new ArrayList<>(segments), from, to, clusterer.speakerCount()).run();
        segments.clear();
        segments.addAll(refined.segments);
        speakerCountAfterRefine = refined.speakers;
        return segments();
    }

    /** Everything a background preview needs, copied on the audio thread. */
    public static final class PreviewInput {
        final FrameStore frames;
        final List<SpeakerSegment> segments;
        final int[] from;
        final int[] to;
        final int liveSpeakers;

        PreviewInput(FrameStore frames, List<SpeakerSegment> segments, int[] from, int[] to, int liveSpeakers) {
            this.frames = frames;
            this.segments = segments;
            this.from = from;
            this.to = to;
            this.liveSpeakers = liveSpeakers;
        }

        public int segmentCount() {
            return segments.size();
        }
    }

    /** Audio thread: snapshot of the committed segments and their frames for {@link #runPreview}. */
    public PreviewInput capturePreview() {
        int n = segments.size();
        int[] from = new int[n];
        int[] to = new int[n];
        for (int i = 0; i < n; i++) {
            from[i] = segmentFrames.get(i)[0];
            to[i] = segmentFrames.get(i)[1];
        }
        return new PreviewInput(frames.copy(), new ArrayList<>(segments), from, to, clusterer.speakerCount());
    }

    /** Any thread: the offline pass over a preview snapshot (does not touch the live state). */
    public static OfflineDiarizer.Result runPreview(PreviewInput in) {
        return new OfflineDiarizer(in.frames, in.segments, in.from, in.to, in.liveSpeakers).run();
    }

    /** After {@link #refine()}: final index of each live speaker label (-1 if that voice vanished). */
    public int[] liveToFinal() {
        if (refined != null) {
            return refined.liveToFinal;
        }
        int[] identity = new int[Math.max(1, clusterer.speakerCount())];
        for (int i = 0; i < identity.length; i++) {
            identity[i] = i;
        }
        return identity;
    }

    /** One aggregated voice profile per speaker index (labels as currently held, i.e. after refine()). */
    public List<SpeakerProfile> speakerProfiles() {
        if (refined != null) {
            return refined.profiles;
        }
        int n = speakerCount();
        List<SpeakerProfile> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new SpeakerProfile());
        }
        for (int i = 0; i < segments.size() && i < segmentProfiles.size(); i++) {
            int s = segments.get(i).speaker;
            if (s >= 0 && s < n) {
                out.get(s).addAll(segmentProfiles.get(i));
            }
        }
        return out;
    }

    private static SpeakerProfile profileOf(List<FrameFeatures> frames, int from, int to) {
        SpeakerProfile p = new SpeakerProfile();
        for (int i = from; i < to; i++) {
            p.add(frames.get(i));
        }
        return p;
    }

    private static long frameMs() {
        return FeatureExtractor.HOP * 1000L / FeatureExtractor.SAMPLE_RATE;
    }
}
