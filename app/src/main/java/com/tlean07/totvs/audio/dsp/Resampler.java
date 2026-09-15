package com.tlean07.totvs.audio.dsp;

/**
 * Converts PCM16 from an arbitrary rate/channel count to 16 kHz mono: channels are
 * averaged, a windowed-sinc low-pass removes content above the new Nyquist and the
 * result is linearly interpolated. Used when a phone refuses to open the microphone
 * at 16 kHz and when decoding imported files (44.1/48 kHz).
 */
public final class Resampler {

    private static final int TAPS = 47;

    private final int inputRate;
    private final int channels;
    private final int outputRate;
    private final float[] fir;
    private final float[] history = new float[TAPS];
    /** Fractional position of the next output sample, measured from {@link #previous}. */
    private double phase;
    private float previous;

    public Resampler(int inputRate, int channels, int outputRate) {
        if (inputRate <= 0 || channels <= 0 || outputRate <= 0) {
            throw new IllegalArgumentException("invalid resampler configuration");
        }
        this.inputRate = inputRate;
        this.channels = channels;
        this.outputRate = outputRate;
        this.fir = inputRate > outputRate ? lowPass(outputRate * 0.45 / inputRate) : null;
    }

    public boolean isIdentity() {
        return inputRate == outputRate && channels == 1;
    }

    /** Upper bound on the output length for {@code inputFrames} interleaved frames. */
    public int maxOutput(int inputFrames) {
        return (int) Math.ceil(inputFrames * (double) outputRate / inputRate) + 2;
    }

    /**
     * @param in       interleaved PCM16, {@code inLength} samples (frames × channels)
     * @param out      receives mono PCM16 at the output rate
     * @return number of samples written to {@code out}
     */
    public int process(short[] in, int inLength, short[] out) {
        int frames = inLength / channels;
        int written = 0;
        double step = (double) inputRate / outputRate;
        for (int f = 0; f < frames; f++) {
            float mono;
            if (channels == 1) {
                mono = in[f];
            } else {
                int acc = 0;
                for (int c = 0; c < channels; c++) {
                    acc += in[f * channels + c];
                }
                mono = acc / (float) channels;
            }
            float current = fir == null ? mono : filter(mono);
            while (phase < 1.0) {
                float v = (float) (previous + (current - previous) * phase);
                if (written < out.length) {
                    out[written++] = clamp(v);
                }
                phase += step;
            }
            phase -= 1.0;
            previous = current;
        }
        return written;
    }

    private float filter(float sample) {
        System.arraycopy(history, 0, history, 1, TAPS - 1);
        history[0] = sample;
        float acc = 0f;
        for (int i = 0; i < TAPS; i++) {
            acc += fir[i] * history[i];
        }
        return acc;
    }

    private static float[] lowPass(double cutoffFraction) {
        float[] h = new float[TAPS];
        int mid = TAPS / 2;
        double sum = 0;
        for (int i = 0; i < TAPS; i++) {
            int n = i - mid;
            double sinc = n == 0 ? 2 * cutoffFraction
                    : Math.sin(2 * Math.PI * cutoffFraction * n) / (Math.PI * n);
            double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1));
            h[i] = (float) (sinc * window);
            sum += h[i];
        }
        for (int i = 0; i < TAPS; i++) {
            h[i] /= (float) sum;
        }
        return h;
    }

    private static short clamp(float v) {
        if (v > 32767f) {
            return 32767;
        }
        if (v < -32768f) {
            return -32768;
        }
        return (short) Math.round(v);
    }
}
