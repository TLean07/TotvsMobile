package com.tlean07.totvs.audio.dsp;

/**
 * Accumulates the acoustic "fingerprint" of a stretch of speech: mean MFCC
 * c1..c12 over voiced frames plus the mean log2 pitch. Also used as the running
 * centroid of a speaker cluster.
 */
public final class SpeakerProfile {

    public static final int MFCC_DIMS = FrameFeatures.MFCC_COUNT - 1;

    /** Weight of the pitch term against the timbre (MFCC) term in the distance. */
    private static final float PITCH_WEIGHT = 0.35f;
    /** One "unit" of pitch difference ≈ 0.18 octave (~13 %). */
    private static final float PITCH_UNIT_OCTAVES = 0.18f;
    /** Expected between-speaker spread of a mean MFCC, per coefficient (empirical). */
    private static final float[] MFCC_SCALE = {
            1.9f, 1.4f, 1.2f, 1.0f, 0.9f, 0.85f, 0.8f, 0.75f, 0.7f, 0.65f, 0.6f, 0.6f
    };

    private final double[] mfccSum = new double[MFCC_DIMS];
    private final double[] unvoicedSum = new double[MFCC_DIMS];
    private double pitchSum;
    private int voicedFrames;
    private int unvoicedFrames;

    public void add(FrameFeatures f) {
        if (f.voiced()) {
            for (int i = 0; i < MFCC_DIMS; i++) {
                mfccSum[i] += f.mfcc[i + 1];
            }
            pitchSum += Math.log(f.pitchHz) / Math.log(2);
            voicedFrames++;
        } else {
            for (int i = 0; i < MFCC_DIMS; i++) {
                unvoicedSum[i] += f.mfcc[i + 1];
            }
            unvoicedFrames++;
        }
    }

    public void addAll(SpeakerProfile other) {
        for (int i = 0; i < MFCC_DIMS; i++) {
            mfccSum[i] += other.mfccSum[i];
            unvoicedSum[i] += other.unvoicedSum[i];
        }
        pitchSum += other.pitchSum;
        voicedFrames += other.voicedFrames;
        unvoicedFrames += other.unvoicedFrames;
    }

    /**
     * Blends {@code other} into this profile as an exponentially weighted
     * centroid whose memory saturates at {@code maxFrames} voiced frames, so a
     * speaker model keeps adapting over a long meeting.
     */
    public void blend(SpeakerProfile other, int maxFrames) {
        if (voicedFrames + other.voicedFrames <= maxFrames) {
            addAll(other);
            return;
        }
        double keep = Math.max(0.0, (maxFrames - other.voicedFrames) / (double) Math.max(1, voicedFrames));
        keep = Math.min(1.0, keep);
        for (int i = 0; i < MFCC_DIMS; i++) {
            mfccSum[i] = mfccSum[i] * keep + other.mfccSum[i];
            unvoicedSum[i] = unvoicedSum[i] * keep + other.unvoicedSum[i];
        }
        pitchSum = pitchSum * keep + other.pitchSum;
        voicedFrames = (int) Math.round(voicedFrames * keep) + other.voicedFrames;
        unvoicedFrames = (int) Math.round(unvoicedFrames * keep) + other.unvoicedFrames;
    }

    public int voicedFrames() {
        return voicedFrames;
    }

    public int frames() {
        return voicedFrames + unvoicedFrames;
    }

    /** Voiced frames are the reliable ones; below this the profile is only a guess. */
    public boolean isReliable() {
        return voicedFrames >= 25;
    }

    public boolean hasPitch() {
        return voicedFrames >= 5;
    }

    public float meanPitchOctaves() {
        return voicedFrames == 0 ? Float.NaN : (float) (pitchSum / voicedFrames);
    }

    public float meanPitchHz() {
        return voicedFrames == 0 ? 0f : (float) Math.pow(2, meanPitchOctaves());
    }

    public float meanMfcc(int i) {
        if (voicedFrames > 0) {
            return (float) (mfccSum[i] / voicedFrames);
        }
        return unvoicedFrames > 0 ? (float) (unvoicedSum[i] / unvoicedFrames) : 0f;
    }

    /**
     * Distance between two profiles: RMS of the scaled MFCC-mean differences,
     * combined with the pitch gap when both sides have enough voiced frames.
     * Values around 1.0 separate "same person" from "somebody else".
     */
    public float distanceTo(SpeakerProfile other) {
        double acc = 0;
        for (int i = 0; i < MFCC_DIMS; i++) {
            double d = (meanMfcc(i) - other.meanMfcc(i)) / MFCC_SCALE[i];
            acc += d * d;
        }
        double timbre = Math.sqrt(acc / MFCC_DIMS);
        if (!hasPitch() || !other.hasPitch()) {
            return (float) timbre;
        }
        double pitch = Math.abs(meanPitchOctaves() - other.meanPitchOctaves()) / PITCH_UNIT_OCTAVES;
        return (float) Math.sqrt((1 - PITCH_WEIGHT) * timbre * timbre + PITCH_WEIGHT * pitch * pitch);
    }

    public void reset() {
        java.util.Arrays.fill(mfccSum, 0);
        java.util.Arrays.fill(unvoicedSum, 0);
        pitchSum = 0;
        voicedFrames = 0;
        unvoicedFrames = 0;
    }

    public SpeakerProfile copy() {
        SpeakerProfile p = new SpeakerProfile();
        p.addAll(this);
        return p;
    }
}
