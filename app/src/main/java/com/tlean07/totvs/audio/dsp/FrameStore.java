package com.tlean07.totvs.audio.dsp;

/**
 * Compact store of every speech frame of a recording (MFCC c1..c12, pitch and
 * time) so the whole conversation can be re-clustered once it is over. One
 * hour of speech is about 225k frames ≈ 12 MB.
 */
public final class FrameStore {

    /** c1..c19 (c0 is energy, not voice). */
    public static final int DIMS = FrameFeatures.MFCC_COUNT - 1;
    /** Layout per frame: c1..c19, pitch (log2 Hz or NaN), time ms, c0 (log energy). */
    static final int STRIDE = DIMS + 3;
    private static final int MAX_FRAMES = 4 * 3600 * 1000 / 16; // 4 h

    private float[] data = new float[STRIDE * 4096];
    private int size;

    public int size() {
        return size;
    }

    /** Snapshot for a background pass while the audio thread keeps adding frames. */
    public synchronized FrameStore copy() {
        FrameStore c = new FrameStore();
        c.data = java.util.Arrays.copyOf(data, Math.max(STRIDE, size * STRIDE));
        c.size = size;
        return c;
    }

    public synchronized boolean add(FrameFeatures f) {
        if (size >= MAX_FRAMES) {
            return false;
        }
        if ((size + 1) * STRIDE > data.length) {
            data = java.util.Arrays.copyOf(data, Math.min(MAX_FRAMES, data.length / STRIDE * 2) * STRIDE);
        }
        int o = size * STRIDE;
        for (int i = 0; i < DIMS; i++) {
            data[o + i] = f.mfcc[i + 1];
        }
        data[o + DIMS] = f.voiced() ? (float) (Math.log(f.pitchHz) / Math.log(2)) : Float.NaN;
        data[o + DIMS + 1] = f.timeMs;
        data[o + DIMS + 2] = f.mfcc[0];
        size++;
        return true;
    }

    public float mfcc(int frame, int coefficient) {
        return data[frame * STRIDE + coefficient];
    }

    /** log2 of the pitch in Hz, or NaN for an unvoiced frame. */
    public float pitchOctaves(int frame) {
        return data[frame * STRIDE + DIMS];
    }

    public long timeMs(int frame) {
        return (long) data[frame * STRIDE + DIMS + 1];
    }

    /** MFCC c0 – a log-energy proxy. */
    public float energy(int frame) {
        return data[frame * STRIDE + DIMS + 2];
    }

    public void addTo(SpeakerProfile profile, int frame) {
        float[] mfcc = new float[SpeakerProfile.MFCC_DIMS];
        for (int i = 0; i < mfcc.length; i++) {
            mfcc[i] = mfcc(frame, i);
        }
        profile.addRaw(mfcc, pitchOctaves(frame));
    }
}
