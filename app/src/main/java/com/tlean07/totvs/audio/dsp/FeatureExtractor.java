package com.tlean07.totvs.audio.dsp;

/**
 * Streaming front-end: takes 16 kHz mono PCM16 samples and emits one
 * {@link FrameFeatures} every {@value #HOP} samples (16 ms) computed over a
 * {@value #FRAME} sample (32 ms) Hamming window – log-energy, 13 MFCCs (26 mel
 * bands, orthonormal DCT-II), autocorrelation pitch and spectral centroid.
 *
 * Everything is pre-allocated so a frame costs a 512-point FFT and a small
 * autocorrelation scan; a phone CPU does this hundreds of times faster than real time.
 */
public final class FeatureExtractor {

    public static final int SAMPLE_RATE = 16_000;
    public static final int FRAME = 512;
    public static final int HOP = 256;
    public static final int MEL_BANDS = 26;

    private static final float PRE_EMPHASIS = 0.97f;
    private static final float MEL_MIN_HZ = 80f;
    private static final float MEL_MAX_HZ = 7_000f;
    private static final int PITCH_MIN_LAG = SAMPLE_RATE / 400;   // 400 Hz
    private static final int PITCH_MAX_LAG = SAMPLE_RATE / 70;    // 70 Hz
    private static final float VOICING_THRESHOLD = 0.50f;

    public interface Listener {
        void onFrame(FrameFeatures frame);
    }

    private final Fft fft = new Fft(FRAME);
    private final float[] window = new float[FRAME];
    private final float[] ring = new float[FRAME];
    private final float[] frame = new float[FRAME];
    private final float[] emphasised = new float[FRAME];
    private final float[] power = new float[FRAME / 2 + 1];
    private final float[] mel = new float[MEL_BANDS];
    private final float[][] melFilters = new float[MEL_BANDS][];
    private final int[] melStart = new int[MEL_BANDS];
    private final float[][] dct = new float[FrameFeatures.MFCC_COUNT][MEL_BANDS];
    private final float[] binHz = new float[FRAME / 2 + 1];
    private final float[] autocorr = new float[PITCH_MAX_LAG + 1];
    private final FrameFeatures features = new FrameFeatures();
    private final Listener listener;

    private int filled;
    private long samplesSeen;
    private float lastSample;

    public FeatureExtractor(Listener listener) {
        this.listener = listener;
        for (int i = 0; i < FRAME; i++) {
            window[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1)));
        }
        for (int k = 0; k <= FRAME / 2; k++) {
            binHz[k] = (float) k * SAMPLE_RATE / FRAME;
        }
        buildMelFilters();
        for (int c = 0; c < FrameFeatures.MFCC_COUNT; c++) {
            float scale = (float) Math.sqrt((c == 0 ? 1.0 : 2.0) / MEL_BANDS);
            for (int m = 0; m < MEL_BANDS; m++) {
                dct[c][m] = scale * (float) Math.cos(Math.PI * c * (m + 0.5) / MEL_BANDS);
            }
        }
    }

    /** Milliseconds of audio consumed so far. */
    public long positionMs() {
        return samplesSeen * 1000L / SAMPLE_RATE;
    }

    public void process(short[] pcm, int length) {
        for (int i = 0; i < length; i++) {
            ring[filled++] = pcm[i] / 32768f;
            samplesSeen++;
            if (filled == FRAME) {
                analyse();
                System.arraycopy(ring, HOP, ring, 0, FRAME - HOP);
                filled = FRAME - HOP;
            }
        }
    }

    /** Flushes a trailing partial frame (zero padded) – call once at end of stream. */
    public void finish() {
        if (filled > FRAME / 2) {
            for (int i = filled; i < FRAME; i++) {
                ring[i] = 0f;
            }
            filled = FRAME;
            analyse();
        }
        filled = 0;
    }

    public void reset() {
        filled = 0;
        samplesSeen = 0;
        lastSample = 0f;
    }

    private void analyse() {
        FrameFeatures f = features;
        f.timeMs = (samplesSeen - FRAME) * 1000L / SAMPLE_RATE;

        // Energy on the raw frame.
        double sum = 0;
        for (int i = 0; i < FRAME; i++) {
            frame[i] = ring[i];
            sum += ring[i] * ring[i];
        }
        f.energyDb = (float) (10.0 * Math.log10(sum / FRAME + 1e-10));

        // Pre-emphasis + window for the spectral features.
        float prev = lastSample;
        for (int i = 0; i < FRAME; i++) {
            emphasised[i] = (frame[i] - PRE_EMPHASIS * prev) * window[i];
            prev = frame[i];
        }
        lastSample = frame[HOP - 1];
        fft.powerSpectrum(emphasised, power);

        // Spectral centroid.
        double num = 0;
        double den = 0;
        for (int k = 1; k <= FRAME / 2; k++) {
            num += binHz[k] * power[k];
            den += power[k];
        }
        f.centroidHz = den > 0 ? (float) (num / den) : 0f;

        // Log-mel energies and MFCC.
        for (int m = 0; m < MEL_BANDS; m++) {
            float[] filter = melFilters[m];
            int start = melStart[m];
            double acc = 0;
            for (int j = 0; j < filter.length; j++) {
                acc += filter[j] * power[start + j];
            }
            mel[m] = (float) Math.log(acc + 1e-10);
        }
        for (int c = 0; c < FrameFeatures.MFCC_COUNT; c++) {
            float acc = 0f;
            float[] row = dct[c];
            for (int m = 0; m < MEL_BANDS; m++) {
                acc += row[m] * mel[m];
            }
            f.mfcc[c] = acc;
        }

        pitch(f);
        listener.onFrame(f);
    }

    /**
     * Normalised autocorrelation pitch tracker over 70–400 Hz. Picks the shortest
     * lag whose peak is within 10 % of the global maximum to avoid octave-down errors.
     */
    private void pitch(FrameFeatures f) {
        float e0 = 0f;
        for (int i = 0; i < FRAME; i++) {
            e0 += frame[i] * frame[i];
        }
        if (e0 < 1e-6f) {
            f.pitchHz = 0f;
            f.voicing = 0f;
            return;
        }
        float best = 0f;
        int bestLag = 0;
        float[] r = autocorr;
        for (int lag = PITCH_MIN_LAG; lag <= PITCH_MAX_LAG; lag++) {
            float acc = 0f;
            float eLag = 0f;
            float eBase = 0f;
            int limit = FRAME - lag;
            for (int i = 0; i < limit; i++) {
                float a = frame[i];
                float b = frame[i + lag];
                acc += a * b;
                eBase += a * a;
                eLag += b * b;
            }
            float norm = (float) Math.sqrt(eBase * eLag) + 1e-9f;
            r[lag] = acc / norm;
            if (r[lag] > best) {
                best = r[lag];
                bestLag = lag;
            }
        }
        f.voicing = Math.max(0f, best);
        if (best < VOICING_THRESHOLD || bestLag == 0) {
            f.pitchHz = 0f;
            return;
        }
        int chosen = bestLag;
        for (int lag = PITCH_MIN_LAG; lag < bestLag; lag++) {
            if (r[lag] >= 0.90f * best && isLocalPeak(r, lag)) {
                chosen = lag;
                break;
            }
        }
        f.pitchHz = (float) SAMPLE_RATE / chosen;
    }

    private static boolean isLocalPeak(float[] r, int lag) {
        return lag > 0 && lag < r.length - 1 && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1];
    }

    private void buildMelFilters() {
        float melMin = hzToMel(MEL_MIN_HZ);
        float melMax = hzToMel(MEL_MAX_HZ);
        float[] edgesHz = new float[MEL_BANDS + 2];
        for (int i = 0; i < edgesHz.length; i++) {
            edgesHz[i] = melToHz(melMin + (melMax - melMin) * i / (MEL_BANDS + 1));
        }
        for (int m = 0; m < MEL_BANDS; m++) {
            float lo = edgesHz[m];
            float mid = edgesHz[m + 1];
            float hi = edgesHz[m + 2];
            int kLo = (int) Math.ceil(lo * FRAME / SAMPLE_RATE);
            int kHi = (int) Math.floor(hi * FRAME / SAMPLE_RATE);
            kHi = Math.min(kHi, FRAME / 2);
            if (kHi < kLo) {
                kHi = kLo;
            }
            float[] filter = new float[kHi - kLo + 1];
            for (int k = kLo; k <= kHi; k++) {
                float hz = binHz[k];
                float w;
                if (hz <= mid) {
                    w = (hz - lo) / Math.max(mid - lo, 1f);
                } else {
                    w = (hi - hz) / Math.max(hi - mid, 1f);
                }
                filter[k - kLo] = Math.max(0f, w);
            }
            melFilters[m] = filter;
            melStart[m] = kLo;
        }
    }

    private static float hzToMel(float hz) {
        return (float) (2595.0 * Math.log10(1.0 + hz / 700.0));
    }

    private static float melToHz(float mel) {
        return (float) (700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0));
    }
}
