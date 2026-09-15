package com.tlean07.totvs.audio.dsp;

/**
 * In-place radix-2 complex FFT sized for one analysis frame, plus a helper that
 * turns a real frame into its one-sided power spectrum. Pure Java, no allocation
 * per call: the caller keeps one instance per thread.
 */
public final class Fft {

    private final int n;
    private final int levels;
    private final float[] cosTable;
    private final float[] sinTable;
    private final float[] re;
    private final float[] im;

    public Fft(int n) {
        if (Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be a power of two: " + n);
        }
        this.n = n;
        this.levels = 31 - Integer.numberOfLeadingZeros(n);
        cosTable = new float[n / 2];
        sinTable = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cosTable[i] = (float) Math.cos(2 * Math.PI * i / n);
            sinTable[i] = (float) Math.sin(2 * Math.PI * i / n);
        }
        re = new float[n];
        im = new float[n];
    }

    public int size() {
        return n;
    }

    /**
     * Computes |X[k]|^2 for k = 0..n/2 of the real signal {@code frame} (length n).
     * {@code out} must have n/2 + 1 entries.
     */
    public void powerSpectrum(float[] frame, float[] out) {
        System.arraycopy(frame, 0, re, 0, n);
        java.util.Arrays.fill(im, 0f);
        transform(re, im);
        for (int k = 0; k <= n / 2; k++) {
            out[k] = re[k] * re[k] + im[k] * im[k];
        }
    }

    /** Standard iterative Cooley-Tukey FFT (Nayuki-style), in place. */
    public void transform(float[] real, float[] imag) {
        // Bit-reversed addressing permutation
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - levels);
            if (j > i) {
                float t = real[i];
                real[i] = real[j];
                real[j] = t;
                t = imag[i];
                imag[i] = imag[j];
                imag[j] = t;
            }
        }
        for (int size = 2; size <= n; size *= 2) {
            int half = size / 2;
            int step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    int l = j + half;
                    float tre = real[l] * cosTable[k] + imag[l] * sinTable[k];
                    float tim = -real[l] * sinTable[k] + imag[l] * cosTable[k];
                    real[l] = real[j] - tre;
                    imag[l] = imag[j] - tim;
                    real[j] += tre;
                    imag[j] += tim;
                }
            }
        }
    }
}
