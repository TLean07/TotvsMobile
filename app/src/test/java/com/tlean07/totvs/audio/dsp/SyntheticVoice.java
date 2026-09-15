package com.tlean07.totvs.audio.dsp;

import java.util.Random;

/**
 * Test helper: a crude "voice" – a harmonic series at a given pitch shaped by
 * two resonances (formants) – enough for the diarizer to tell two of them apart
 * without depending on real recordings.
 */
final class SyntheticVoice {

    final float pitchHz;
    final float f1;
    final float f2;
    private final Random random;

    SyntheticVoice(float pitchHz, float f1, float f2, long seed) {
        this.pitchHz = pitchHz;
        this.f1 = f1;
        this.f2 = f2;
        this.random = new Random(seed);
    }

    /** Fills {@code out} with {@code seconds} of speech-like audio, returns the sample count. */
    int speak(short[] out, int offset, double seconds) {
        int n = (int) (seconds * FeatureExtractor.SAMPLE_RATE);
        double phase = 0;
        double vibrato = random.nextDouble() * Math.PI;
        for (int i = 0; i < n; i++) {
            double t = i / (double) FeatureExtractor.SAMPLE_RATE;
            double f0 = pitchHz * (1 + 0.03 * Math.sin(2 * Math.PI * 5 * t + vibrato));
            phase += 2 * Math.PI * f0 / FeatureExtractor.SAMPLE_RATE;
            double s = 0;
            for (int h = 1; h <= 30; h++) {
                double fh = f0 * h;
                if (fh > 7_000) {
                    break;
                }
                double gain = resonance(fh, f1, 90) + resonance(fh, f2, 140) + 0.02;
                s += gain * Math.sin(h * phase) / h;
            }
            // syllable-like amplitude modulation so energy/VAD behave like speech
            double env = 0.55 + 0.45 * Math.sin(2 * Math.PI * 3.5 * t + vibrato);
            s = s * env * 0.35 + (random.nextDouble() - 0.5) * 0.002;
            out[offset + i] = (short) Math.max(-32768, Math.min(32767, s * 32767));
        }
        return n;
    }

    private static double resonance(double f, double centre, double bandwidth) {
        double d = (f - centre) / bandwidth;
        return 1.0 / (1.0 + d * d);
    }

    static int silence(short[] out, int offset, double seconds) {
        int n = (int) (seconds * FeatureExtractor.SAMPLE_RATE);
        for (int i = 0; i < n; i++) {
            out[offset + i] = (short) ((i * 7919) % 5 - 2);
        }
        return n;
    }
}
