package com.tlean07.totvs.audio;

import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.TranscriptAssembler;
import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.asr.TranscriptWord;
import com.tlean07.totvs.audio.asr.TranscriptionEngine;
import com.tlean07.totvs.audio.asr.VoskEngine;
import com.tlean07.totvs.audio.dsp.FeatureExtractor;
import com.tlean07.totvs.audio.dsp.OfflineDiarizer;
import com.tlean07.totvs.audio.dsp.SpeakerDiarizer;
import com.tlean07.totvs.audio.dsp.SpeakerSegment;
import com.tlean07.totvs.audio.dsp.WavIo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything that happens to the PCM stream once it exists, shared by the live
 * recorder and the file importer: WAV persistence, speaker diarization,
 * speech recognition and the merge of both into speaker turns. Snapshots for
 * the UI are produced at most every {@value #SNAPSHOT_INTERVAL_MS} ms.
 */
public final class AudioPipeline {

    /** Immutable view of the transcript for the UI. */
    public static final class Snapshot {
        public final List<TranscriptTurn> turns;
        public final int speakerCount;
        /** Index of who is speaking now, -1 for nobody, {@link SpeakerDiarizer#UNKNOWN} while undecided. */
        public final int currentSpeaker;
        public final boolean speaking;
        public final long positionMs;
        /** Milliseconds of speech per speaker (index = speaker). */
        public final long[] talkTimeMs;
        public final int asrBacklog;

        Snapshot(List<TranscriptTurn> turns, int speakerCount, int currentSpeaker, boolean speaking,
                 long positionMs, long[] talkTimeMs, int asrBacklog) {
            this.turns = turns;
            this.speakerCount = speakerCount;
            this.currentSpeaker = currentSpeaker;
            this.speaking = speaking;
            this.positionMs = positionMs;
            this.talkTimeMs = talkTimeMs;
            this.asrBacklog = asrBacklog;
        }
    }

    public interface Listener {
        /** Called on the pipeline (capture) thread. */
        void onSnapshot(Snapshot snapshot);

        /** Level of the last chunk in dBFS plus the audio clock, on the capture thread. */
        void onLevel(float dbfs, long positionMs);

        /**
         * A background re-clustering changed the speaker labels shown so far:
         * {@code oldToNew[i]} is the new index of what used to be speaker i (-1 = gone).
         * Background thread.
         */
        default void onSpeakersRemapped(int[] oldToNew) {
        }
    }

    private static final long SNAPSHOT_INTERVAL_MS = 250;
    /** Audio between background re-clusterings of everything heard so far. */
    private static final long PREVIEW_INTERVAL_MS = 12_000;
    /** Beyond this much audio the preview is skipped (the final pass still runs). */
    private static final long PREVIEW_MAX_AUDIO_MS = 45 * 60_000;

    private final SpeakerDiarizer diarizer;
    private final TranscriptAssembler assembler = new TranscriptAssembler();
    private final Listener listener;
    @Nullable
    private volatile TranscriptionEngine asr;
    @Nullable
    private final WavIo.Writer wav;
    private final Object lock = new Object();
    private volatile boolean dirty;
    private long lastSnapshotMs;
    private long lastFlushMs;
    private boolean finished;

    // --- live preview state (guarded by lock) ---
    /** Committed live segments, unmapped, in order. */
    private final List<SpeakerSegment> liveSegments = new ArrayList<>();
    /** live label → displayed label; null until the first preview. */
    @Nullable
    private int[] displayMap;
    private int displaySpeakers;
    /** Refined labels for the first {@link #previewedCount} live segments. */
    private List<SpeakerSegment> previewSegments = new ArrayList<>();
    private int previewedCount;
    private volatile boolean previewRunning;
    private long lastPreviewAudioMs;
    private long lastPreviewCostMs;
    @Nullable
    private int[] displayedToFinal;
    @Nullable
    private final File wavFile;
    @Nullable
    private volatile com.tlean07.totvs.audio.diar.NeuralDiarizer neural;
    @Nullable
    private volatile com.tlean07.totvs.audio.diar.NeuralDiarizer.Progress neuralProgress;
    /** Which pass produced the final labels ("neural" or "dsp"). */
    private volatile String finalPass = "dsp";

    public AudioPipeline(@Nullable File wavFile, Listener listener) throws IOException {
        this.listener = listener;
        this.wavFile = wavFile;
        this.wav = wavFile == null ? null : new WavIo.Writer(wavFile, FeatureExtractor.SAMPLE_RATE, 1);
        this.diarizer = new SpeakerDiarizer(new SpeakerDiarizer.Listener() {
            @Override
            public void onSpeechStart(long tMs) {
                dirty = true;
            }

            @Override
            public void onProvisional(SpeakerSegment segment) {
                synchronized (lock) {
                    assembler.setOpenSegment(displayMap == null ? segment
                            : new SpeakerSegment(segment.startMs, segment.endMs, mapLive(segment.speaker, false),
                                    true, segment.distance));
                }
                dirty = true;
            }

            @Override
            public void onSegment(SpeakerSegment segment, boolean created) {
                synchronized (lock) {
                    liveSegments.add(segment);
                    assembler.addSegment(displayMap == null ? segment
                            : new SpeakerSegment(segment.startMs, segment.endMs, mapLive(segment.speaker, true),
                                    false, segment.distance));
                }
                dirty = true;
            }

            @Override
            public void onSpeechEnd(long tMs) {
                synchronized (lock) {
                    assembler.clearOpenSegment();
                }
                dirty = true;
            }
        });
    }

    /**
     * Uses the neural speaker models for the final pass (needs the 16 kHz WAV this
     * pipeline writes). Without it, or if it fails, the DSP pass runs.
     */
    public void setNeural(@Nullable com.tlean07.totvs.audio.diar.NeuralDiarizer neural,
                          @Nullable com.tlean07.totvs.audio.diar.NeuralDiarizer.Progress progress) {
        this.neural = neural;
        this.neuralProgress = progress;
    }

    public String finalPass() {
        return finalPass;
    }

    /** Plugs in the recogniser (created with {@link #asrListener()}); call before the first {@link #feed}. */
    public void attachAsr(@Nullable TranscriptionEngine engine) {
        this.asr = engine;
    }

    /** Listener the ASR engine must be created with so its output lands here. */
    public TranscriptionEngine.Listener asrListener() {
        return new TranscriptionEngine.Listener() {
            @Override
            public void onPartial(String text) {
                synchronized (lock) {
                    assembler.setPartial(VocabularioTotvs.corrigir(text), diarizer.positionMs());
                }
                dirty = true;
                maybeSnapshot(false);
            }

            @Override
            public void onWords(List<TranscriptWord> words) {
                List<TranscriptWord> fixed = new ArrayList<>(words.size());
                for (TranscriptWord w : words) {
                    fixed.add(new TranscriptWord(VocabularioTotvs.corrigir(w.text), w.startMs, w.endMs, w.confidence));
                }
                synchronized (lock) {
                    assembler.addWords(fixed);
                }
                dirty = true;
                maybeSnapshot(false);
            }
        };
    }

    public boolean hasAsr() {
        return asr != null;
    }

    public long positionMs() {
        return diarizer.positionMs();
    }

    /** Feeds 16 kHz mono PCM; safe to call from one producer thread. */
    public void feed(short[] pcm, int length) throws IOException {
        if (finished || length <= 0) {
            return;
        }
        if (wav != null) {
            wav.write(pcm, length);
            long now = System.currentTimeMillis();
            if (now - lastFlushMs > 2_000) {
                lastFlushMs = now;
                wav.flush();
            }
        }
        double sum = 0;
        for (int i = 0; i < length; i++) {
            double s = pcm[i] / 32768.0;
            sum += s * s;
        }
        float dbfs = (float) (10 * Math.log10(sum / length + 1e-10));
        diarizer.process(pcm, length);
        TranscriptionEngine engine = asr;
        if (engine != null) {
            engine.accept(pcm, length);
        }
        listener.onLevel(dbfs, diarizer.positionMs());
        maybePreview(false);
        maybeSnapshot(false);
    }

    /** Closes the open utterance (pause). */
    public void flush() {
        diarizer.flush();
        TranscriptionEngine engine = asr;
        if (engine != null) {
            engine.flush();
        }
        maybePreview(true);
        maybeSnapshot(true);
    }

    // --- live preview: the offline pass over everything heard so far, in the background ---

    /** live label → displayed label, allocating a new displayed index for a voice the preview never saw. */
    private int mapLive(int live, boolean allocate) {
        if (displayMap == null) {
            return live;
        }
        if (live >= 0 && live < displayMap.length && displayMap[live] >= 0) {
            return displayMap[live];
        }
        if (!allocate || live < 0) {
            return SpeakerDiarizer.UNKNOWN;
        }
        if (live >= displayMap.length) {
            int old = displayMap.length;
            displayMap = java.util.Arrays.copyOf(displayMap, live + 1);
            java.util.Arrays.fill(displayMap, old, displayMap.length, -1);
        }
        displayMap[live] = displaySpeakers++;
        return displayMap[live];
    }

    private void maybePreview(boolean force) {
        if (finished || previewRunning) {
            return;
        }
        long pos = diarizer.positionMs();
        if (pos > PREVIEW_MAX_AUDIO_MS) {
            return;
        }
        long wait = Math.max(PREVIEW_INTERVAL_MS, 3 * lastPreviewCostMs);
        if (!force && pos - lastPreviewAudioMs < wait) {
            return;
        }
        if (diarizer.segments().size() < 2) {
            return;
        }
        SpeakerDiarizer.PreviewInput input = diarizer.capturePreview();
        lastPreviewAudioMs = pos;
        previewRunning = true;
        com.tlean07.totvs.util.AppExecutors.io(() -> {
            long t0 = System.currentTimeMillis();
            try {
                OfflineDiarizer.Result result = SpeakerDiarizer.runPreview(input);
                applyPreview(result, input.segmentCount());
            } catch (RuntimeException e) {
                com.tlean07.totvs.util.Logger.w("AudioPipeline", "prévia de vozes falhou: " + e);
            } finally {
                lastPreviewCostMs = System.currentTimeMillis() - t0;
                previewRunning = false;
            }
            dirty = true;
            maybeSnapshot(true);
        });
    }

    private void applyPreview(OfflineDiarizer.Result result, int coveredSegments) {
        int[] oldToNew = null;
        synchronized (lock) {
            if (finished) {
                return;
            }
            List<SpeakerSegment> before = new ArrayList<>(assembler.segments());
            int beforeSpeakers = Math.max(displaySpeakers, diarizer.speakerCount());
            previewSegments = new ArrayList<>(result.segments);
            previewedCount = Math.min(coveredSegments, liveSegments.size());
            displayMap = result.liveToFinal.clone();
            displaySpeakers = result.speakers;
            assembler.segments().clear();
            assembler.segments().addAll(previewSegments);
            for (int i = previewedCount; i < liveSegments.size(); i++) {
                SpeakerSegment s = liveSegments.get(i);
                assembler.segments().add(new SpeakerSegment(s.startMs, s.endMs, mapLive(s.speaker, true), false, s.distance));
            }
            if (!before.isEmpty()) {
                oldToNew = overlapMap(before, beforeSpeakers, assembler.segments(), displaySpeakers);
            }
        }
        if (oldToNew != null) {
            listener.onSpeakersRemapped(oldToNew);
        }
    }

    /** For each label of {@code from}, the label of {@code to} it overlaps most in time (-1 = none). */
    private static int[] overlapMap(List<SpeakerSegment> from, int fromCount, List<SpeakerSegment> to, int toCount) {
        int fc = Math.max(1, fromCount);
        int tc = Math.max(1, toCount);
        long[][] overlap = new long[fc][tc];
        for (SpeakerSegment a : from) {
            if (a.speaker < 0 || a.speaker >= fc) {
                continue;
            }
            for (SpeakerSegment b : to) {
                if (b.speaker < 0 || b.speaker >= tc) {
                    continue;
                }
                long ov = Math.min(a.endMs, b.endMs) - Math.max(a.startMs, b.startMs);
                if (ov > 0) {
                    overlap[a.speaker][b.speaker] += ov;
                }
            }
        }
        int[] map = new int[fc];
        for (int i = 0; i < fc; i++) {
            int best = -1;
            long bv = 0;
            for (int j = 0; j < tc; j++) {
                if (overlap[i][j] > bv) {
                    bv = overlap[i][j];
                    best = j;
                }
            }
            map[i] = best;
        }
        return map;
    }

    /**
     * Ends the stream: flushes, waits for the recogniser to catch up, runs the
     * second diarization pass and closes the WAV. Returns the final turns.
     */
    public List<TranscriptTurn> finish() throws IOException {
        if (finished) {
            return turns();
        }
        finished = true;
        diarizer.flush();
        TranscriptionEngine engine = asr;
        if (engine != null) {
            engine.flush();
            if (engine instanceof VoskEngine) {
                try {
                    ((VoskEngine) engine).drain();
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            engine.close();
        }
        // wait for a preview still running so it cannot overwrite the final labels
        for (int i = 0; i < 100 && previewRunning; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<SpeakerSegment> displayed;
        int displayedCount;
        synchronized (lock) {
            displayed = new ArrayList<>(assembler.segments());
            displayedCount = Math.max(displaySpeakers, diarizer.speakerCount());
        }
        if (wav != null) {
            wav.close();
        }
        List<SpeakerSegment> refined = null;
        com.tlean07.totvs.audio.diar.NeuralDiarizer nn = neural;
        if (nn != null && wavFile != null && wavFile.isFile() && wavFile.length() > 44) {
            try {
                long t0 = System.currentTimeMillis();
                List<SpeakerSegment> segs = nn.run(wavFile, diarizer.frames(), neuralProgress);
                if (!segs.isEmpty()) {
                    diarizer.adopt(segs);
                    refined = segs;
                    finalPass = "neural";
                    com.tlean07.totvs.util.Logger.d("AudioPipeline", "vozes (neural): " + diarizer.speakerCount()
                            + " pessoas em " + (System.currentTimeMillis() - t0) + " ms");
                }
            } catch (IOException | RuntimeException | UnsatisfiedLinkError | OutOfMemoryError e) {
                com.tlean07.totvs.util.Logger.w("AudioPipeline", "separação neural falhou, usando DSP: " + e);
            }
        }
        if (refined == null) {
            refined = diarizer.refine();
            finalPass = "dsp";
        }
        synchronized (lock) {
            displayedToFinal = overlapMap(displayed, displayedCount, refined, diarizer.speakerCount());
            assembler.clearOpenSegment();
            assembler.setPartial("", diarizer.positionMs());
            assembler.segments().clear();
            assembler.segments().addAll(refined);
        }
        maybeSnapshot(true);
        return turns();
    }

    public List<TranscriptTurn> turns() {
        synchronized (lock) {
            return assembler.turns();
        }
    }

    public List<SpeakerSegment> segments() {
        synchronized (lock) {
            return new ArrayList<>(assembler.segments());
        }
    }

    public int speakerCount() {
        if (finished) {
            return diarizer.speakerCount();
        }
        synchronized (lock) {
            return displayMap == null ? diarizer.speakerCount() : displaySpeakers;
        }
    }

    /** Aggregated voice profile per speaker (call after {@link #finish()}). */
    public List<com.tlean07.totvs.audio.dsp.SpeakerProfile> speakerProfiles() {
        return diarizer.speakerProfiles();
    }

    /** Final speaker index for each label shown live (names typed during the recording follow it). */
    public int[] liveToFinal() {
        int[] m = displayedToFinal;
        return m != null ? m : diarizer.liveToFinal();
    }

    /** Every recognised word so far (for auto-labelling markers). */
    public List<TranscriptWord> words() {
        synchronized (lock) {
            return new ArrayList<>(assembler.words());
        }
    }

    public Snapshot snapshot() {
        List<TranscriptTurn> turns;
        long[] talk;
        int speakers = Math.max(speakerCount(), 0);
        int current;
        synchronized (lock) {
            current = finished ? diarizer.currentSpeaker() : mapLive(diarizer.currentSpeaker(), false);
            turns = assembler.turns();
            talk = new long[Math.max(speakers, 1)];
            for (SpeakerSegment s : assembler.segments()) {
                if (s.speaker >= 0 && s.speaker < talk.length) {
                    talk[s.speaker] += s.durationMs();
                }
            }
        }
        TranscriptionEngine engine = asr;
        int backlog = engine instanceof VoskEngine ? ((VoskEngine) engine).backlog() : 0;
        return new Snapshot(Collections.unmodifiableList(turns), speakers, current,
                diarizer.isSpeaking(), diarizer.positionMs(), talk, backlog);
    }

    private void maybeSnapshot(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (!dirty || now - lastSnapshotMs < SNAPSHOT_INTERVAL_MS)) {
            return;
        }
        dirty = false;
        lastSnapshotMs = now;
        listener.onSnapshot(snapshot());
    }
}
