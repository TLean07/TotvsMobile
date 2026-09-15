package com.tlean07.totvs.audio;

import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.TranscriptAssembler;
import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.asr.TranscriptWord;
import com.tlean07.totvs.audio.asr.TranscriptionEngine;
import com.tlean07.totvs.audio.asr.VoskEngine;
import com.tlean07.totvs.audio.dsp.FeatureExtractor;
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
    }

    private static final long SNAPSHOT_INTERVAL_MS = 250;

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

    public AudioPipeline(@Nullable File wavFile, Listener listener) throws IOException {
        this.listener = listener;
        this.wav = wavFile == null ? null : new WavIo.Writer(wavFile, FeatureExtractor.SAMPLE_RATE, 1);
        this.diarizer = new SpeakerDiarizer(new SpeakerDiarizer.Listener() {
            @Override
            public void onSpeechStart(long tMs) {
                dirty = true;
            }

            @Override
            public void onProvisional(SpeakerSegment segment) {
                synchronized (lock) {
                    assembler.setOpenSegment(segment);
                }
                dirty = true;
            }

            @Override
            public void onSegment(SpeakerSegment segment, boolean created) {
                synchronized (lock) {
                    assembler.addSegment(segment);
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
        maybeSnapshot(false);
    }

    /** Closes the open utterance (pause). */
    public void flush() {
        diarizer.flush();
        TranscriptionEngine engine = asr;
        if (engine != null) {
            engine.flush();
        }
        maybeSnapshot(true);
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
        List<SpeakerSegment> refined = diarizer.refine();
        synchronized (lock) {
            assembler.clearOpenSegment();
            assembler.setPartial("", diarizer.positionMs());
            assembler.segments().clear();
            assembler.segments().addAll(refined);
        }
        if (wav != null) {
            wav.close();
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
        return diarizer.speakerCount();
    }

    public Snapshot snapshot() {
        List<TranscriptTurn> turns;
        long[] talk;
        int speakers = Math.max(diarizer.speakerCount(), 0);
        synchronized (lock) {
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
        return new Snapshot(Collections.unmodifiableList(turns), speakers, diarizer.currentSpeaker(),
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
