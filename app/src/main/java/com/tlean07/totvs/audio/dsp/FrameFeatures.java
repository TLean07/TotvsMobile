package com.tlean07.totvs.audio.dsp;

/** Acoustic measurements of one 32 ms analysis frame (mutable, reused by the extractor). */
public final class FrameFeatures {

    /** c0..c19: 12 are enough to tell voices apart live, the offline pass uses them all. */
    public static final int MFCC_COUNT = 20;

    /** Start of the frame in milliseconds of audio fed so far. */
    public long timeMs;
    /** Frame energy in dBFS (0 dB = full-scale sine). */
    public float energyDb;
    /** Mel-frequency cepstral coefficients c0..c19 (c0 tracks energy). */
    public final float[] mfcc = new float[MFCC_COUNT];
    /** Fundamental frequency in Hz, or 0 when the frame is unvoiced. */
    public float pitchHz;
    /** Normalised autocorrelation peak (0..1) – how periodic/voiced the frame is. */
    public float voicing;
    /** Spectral centroid in Hz. */
    public float centroidHz;

    public boolean voiced() {
        return pitchHz > 0f;
    }

    public FrameFeatures copy() {
        FrameFeatures f = new FrameFeatures();
        f.timeMs = timeMs;
        f.energyDb = energyDb;
        System.arraycopy(mfcc, 0, f.mfcc, 0, MFCC_COUNT);
        f.pitchHz = pitchHz;
        f.voicing = voicing;
        f.centroidHz = centroidHz;
        return f;
    }
}
