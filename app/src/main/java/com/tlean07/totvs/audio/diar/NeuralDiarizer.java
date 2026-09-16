package com.tlean07.totvs.audio.diar;

import android.content.Context;

import androidx.annotation.Nullable;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.tlean07.totvs.audio.dsp.FeatureExtractor;
import com.tlean07.totvs.audio.dsp.FrameStore;
import com.tlean07.totvs.audio.dsp.Resampler;
import com.tlean07.totvs.audio.dsp.SpeakerProfile;
import com.tlean07.totvs.audio.dsp.SpeakerSegment;
import com.tlean07.totvs.audio.dsp.WavIo;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Speaker separation with neural models (sherpa-onnx): pyannote segmentation
 * finds who-speaks-when (including overlaps and turn changes without a pause),
 * a speaker-embedding network turns every stretch of speech into a voice
 * vector, and agglomerative clustering groups the vectors into people. This is
 * the same technology behind the desktop diarization tools; it replaces the
 * hand-made MFCC clustering whenever the models are installed.
 *
 * Audio longer than {@value #CHUNK_SECONDS} s is processed in chunks (the
 * models want the whole signal in memory) and the chunk labels are linked with
 * the DSP voice profiles of each label.
 */
public final class NeuralDiarizer {

    public interface Progress {
        /** Background thread; percent 0..100. */
        void onProgress(int percent);
    }

    private static final String TAG = "NeuralDiarizer";
    /** Cosine-distance threshold of the clustering (lower = more speakers). Tuned on the test corpus. */
    public static float THRESHOLD = 0.35f;
    public static final int CHUNK_SECONDS = 15 * 60;
    private static final float MIN_ON = 0.3f;
    private static final float MIN_OFF = 0.5f;
    /** Voice-profile distance under which two chunk labels are the same person. */
    private static final float LINK_DISTANCE = 1.2f;

    private final File segmentation;
    private final File embedding;

    public NeuralDiarizer(File segmentation, File embedding) {
        this.segmentation = segmentation;
        this.embedding = embedding;
    }

    /** The models are installed and the native library loads. */
    @Nullable
    public static NeuralDiarizer ifAvailable(Context context) {
        DiarizationModels models = DiarizationModels.get(context);
        if (!models.isReady()) {
            return null;
        }
        try {
            return new NeuralDiarizer(models.segmentationFile(), models.embeddingFile());
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            Logger.w(TAG, "modelos de vozes indisponíveis: " + e);
            return null;
        }
    }

    /**
     * Runs the models over a WAV file (any rate/channels; resampled to 16 kHz) and
     * returns speaker segments in milliseconds, labels 0..n-1 by first appearance.
     *
     * @param frames optional DSP frames of the same audio, used to link chunk labels
     */
    public List<SpeakerSegment> run(File wav, @Nullable FrameStore frames, @Nullable Progress progress) throws IOException {
        float[] audio = read16k(wav);
        int chunkLen = CHUNK_SECONDS * FeatureExtractor.SAMPLE_RATE;
        int chunks = Math.max(1, (audio.length + chunkLen - 1) / chunkLen);
        OfflineSpeakerDiarization sd = create();
        List<SpeakerSegment> out = new ArrayList<>();
        try {
            int next = 0;
            List<SpeakerProfile> known = new ArrayList<>();
            for (int c = 0; c < chunks; c++) {
                int from = c * chunkLen;
                int to = Math.min(audio.length, from + chunkLen);
                float[] part = chunks == 1 ? audio : Arrays.copyOfRange(audio, from, to);
                // process() rather than processWithCallback(): the JNI side expects a Kotlin
                // (Int, Int, Long) -> Int lambda bridge that a Java lambda does not provide.
                if (progress != null) {
                    progress.onProgress(c * 100 / chunks);
                }
                OfflineSpeakerDiarizationSegment[] segs = sd.process(part);
                if (progress != null) {
                    progress.onProgress((c + 1) * 100 / chunks);
                }
                long offsetMs = from * 1000L / FeatureExtractor.SAMPLE_RATE;
                List<SpeakerSegment> local = new ArrayList<>();
                int localCount = 0;
                for (OfflineSpeakerDiarizationSegment s : segs) {
                    long a = offsetMs + (long) (s.getStart() * 1000);
                    long b = offsetMs + (long) (s.getEnd() * 1000);
                    if (b <= a) {
                        continue;
                    }
                    local.add(new SpeakerSegment(a, b, s.getSpeaker(), false, 0f));
                    localCount = Math.max(localCount, s.getSpeaker() + 1);
                }
                // link this chunk's labels to the people seen in earlier chunks
                int[] map = new int[Math.max(1, localCount)];
                if (c == 0 || frames == null) {
                    for (int i = 0; i < map.length; i++) {
                        map[i] = next++;
                    }
                } else {
                    List<SpeakerProfile> mine = profiles(frames, local, localCount);
                    for (int i = 0; i < map.length; i++) {
                        int best = -1;
                        float bestD = LINK_DISTANCE;
                        for (int k = 0; k < known.size(); k++) {
                            float d = mine.get(i).distanceTo(known.get(k));
                            if (d < bestD) {
                                bestD = d;
                                best = k;
                            }
                        }
                        map[i] = best >= 0 ? best : next++;
                    }
                }
                if (frames != null) {
                    List<SpeakerProfile> mine = profiles(frames, local, localCount);
                    for (int i = 0; i < map.length; i++) {
                        while (known.size() <= map[i]) {
                            known.add(new SpeakerProfile());
                        }
                        known.get(map[i]).addAll(mine.get(i));
                    }
                }
                for (SpeakerSegment s : local) {
                    out.add(new SpeakerSegment(s.startMs, s.endMs, map[s.speaker], false, 0f));
                }
            }
        } finally {
            sd.release();
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        out = renumber(out);
        if (frames != null) {
            out = renumber(absorbMinor(out, frames));
        }
        return out;
    }

    /** Share of the conversation below which a "person" is a clustering artefact (a cough, a laugh, cross-talk). */
    private static final double MINOR_SHARE = 0.12;
    private static final long MINOR_MAX_MS = 20_000;

    /**
     * Voices with almost no speech are folded into the voice whose DSP profile is
     * closest: the clustering sometimes splits off a short, oddly-sounding stretch
     * (laughter, a word over the other person) as a third speaker.
     */
    private static List<SpeakerSegment> absorbMinor(List<SpeakerSegment> segs, FrameStore frames) {
        int n = 0;
        for (SpeakerSegment s : segs) {
            n = Math.max(n, s.speaker + 1);
        }
        if (n <= 1) {
            return segs;
        }
        long[] talk = new long[n];
        long total = 0;
        for (SpeakerSegment s : segs) {
            talk[s.speaker] += s.durationMs();
            total += s.durationMs();
        }
        List<SpeakerProfile> profiles = profiles(frames, segs, n);
        int[] map = new int[n];
        for (int i = 0; i < n; i++) {
            map[i] = i;
        }
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            if (talk[i] >= MINOR_MAX_MS || talk[i] >= MINOR_SHARE * total) {
                continue;
            }
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i || map[j] != j || talk[j] < MINOR_SHARE * total) {
                    continue;
                }
                float d = profiles.get(i).distanceTo(profiles.get(j));
                if (d < bestD) {
                    bestD = d;
                    best = j;
                }
            }
            if (best >= 0) {
                map[i] = best;
                changed = true;
                Logger.d(TAG, "voz " + i + " (" + talk[i] + " ms) absorvida pela voz " + best);
            }
        }
        if (!changed) {
            return segs;
        }
        List<SpeakerSegment> out = new ArrayList<>(segs.size());
        for (SpeakerSegment s : segs) {
            out.add(new SpeakerSegment(s.startMs, s.endMs, map[s.speaker], false, s.distance));
        }
        return out;
    }

    private OfflineSpeakerDiarization create() {
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        OfflineSpeakerDiarizationConfig config = new OfflineSpeakerDiarizationConfig(
                new OfflineSpeakerSegmentationModelConfig(
                        new OfflineSpeakerSegmentationPyannoteModelConfig(segmentation.getAbsolutePath(), 0.1f),
                        threads, false, "cpu"),
                new SpeakerEmbeddingExtractorConfig(embedding.getAbsolutePath(), threads, false, "cpu"),
                new FastClusteringConfig(-1, THRESHOLD, false),
                MIN_ON, MIN_OFF);
        return new OfflineSpeakerDiarization(null, config);
    }

    private static float[] read16k(File wav) throws IOException {
        WavIo.Header h = WavIo.readHeader(wav);
        long totalFrames = h.frames();
        if (totalFrames <= 0) {
            return new float[0];
        }
        Resampler rs = h.sampleRate == FeatureExtractor.SAMPLE_RATE && h.channels == 1 ? null
                : new Resampler(h.sampleRate, h.channels, FeatureExtractor.SAMPLE_RATE);
        int capacity = (int) Math.min(Integer.MAX_VALUE / 4, totalFrames * FeatureExtractor.SAMPLE_RATE / h.sampleRate + 16);
        float[] out = new float[capacity];
        int n = 0;
        try (WavIo.Reader r = new WavIo.Reader(wav)) {
            short[] buf = new short[4096 * h.channels];
            short[] res = rs == null ? null : new short[rs.maxOutput(4096)];
            int got;
            while ((got = r.read(buf)) > 0) {
                short[] src = buf;
                int len = got;
                if (rs != null) {
                    len = rs.process(buf, got, res);
                    src = res;
                }
                if (n + len > out.length) {
                    out = Arrays.copyOf(out, Math.max(out.length * 2, n + len));
                }
                for (int i = 0; i < len; i++) {
                    out[n++] = src[i] / 32768f;
                }
            }
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    private static List<SpeakerProfile> profiles(FrameStore frames, List<SpeakerSegment> segs, int count) {
        List<SpeakerProfile> out = new ArrayList<>();
        for (int i = 0; i < Math.max(1, count); i++) {
            out.add(new SpeakerProfile());
        }
        int s = 0;
        for (int f = 0; f < frames.size(); f++) {
            long t = frames.timeMs(f);
            while (s < segs.size() && segs.get(s).endMs <= t) {
                s++;
            }
            if (s < segs.size() && segs.get(s).startMs <= t && segs.get(s).speaker < out.size()) {
                frames.addTo(out.get(segs.get(s).speaker), f);
            }
        }
        return out;
    }

    private static List<SpeakerSegment> renumber(List<SpeakerSegment> in) {
        int[] map = new int[64];
        Arrays.fill(map, -1);
        int next = 0;
        List<SpeakerSegment> out = new ArrayList<>(in.size());
        for (SpeakerSegment s : in) {
            int sp = Math.max(0, Math.min(s.speaker, map.length - 1));
            if (map[sp] < 0) {
                map[sp] = next++;
            }
            out.add(new SpeakerSegment(s.startMs, s.endMs, map[sp], false, s.distance));
        }
        return out;
    }
}
