package com.tlean07.totvs.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

import com.tlean07.totvs.audio.dsp.FeatureExtractor;
import com.tlean07.totvs.audio.dsp.Resampler;
import com.tlean07.totvs.util.Logger;

import java.io.IOException;

/**
 * Microphone capture that always hands out 16 kHz mono PCM16, whatever the
 * hardware offers. Uses the VOICE_RECOGNITION source (flat response, no
 * aggressive gain control that would distort the speaker features) and the
 * platform noise suppressor when the device has one.
 */
public final class AudioCapture {

    public interface Sink {
        /** Called on the capture thread with 16 kHz mono samples. */
        void onPcm(short[] pcm, int length);
    }

    private static final String TAG = "AudioCapture";
    private static final int TARGET_RATE = FeatureExtractor.SAMPLE_RATE;
    private static final int[] CANDIDATE_RATES = {TARGET_RATE, 48_000, 44_100, 32_000, 22_050};
    /** 40 ms of audio per read at the target rate. */
    private static final int CHUNK_MS = 40;

    private AudioRecord record;
    private NoiseSuppressor suppressor;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean muted;
    private int sampleRate;

    public int sampleRate() {
        return sampleRate;
    }

    /** True while the sink receives silence-free audio; {@link #setMuted} drops chunks during a pause. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    @SuppressLint("MissingPermission")
    public void start(Sink sink) throws IOException {
        if (running) {
            return;
        }
        AudioRecord opened = null;
        int rate = 0;
        int bufferBytes = 0;
        for (int candidate : CANDIDATE_RATES) {
            int min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                continue;
            }
            int wanted = Math.max(min, candidate * 2 /* bytes */ * 500 / 1000 /* 0.5 s */);
            try {
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, candidate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, wanted);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    opened = r;
                    rate = candidate;
                    bufferBytes = wanted;
                    break;
                }
                r.release();
            } catch (IllegalArgumentException e) {
                Logger.w(TAG, "taxa " + candidate + " recusada");
            }
        }
        if (opened == null) {
            throw new IOException("O microfone não pôde ser aberto.");
        }
        record = opened;
        sampleRate = rate;
        try {
            if (NoiseSuppressor.isAvailable()) {
                suppressor = NoiseSuppressor.create(record.getAudioSessionId());
                if (suppressor != null) {
                    suppressor.setEnabled(true);
                }
            }
        } catch (RuntimeException e) {
            suppressor = null;
        }
        final Resampler resampler = rate == TARGET_RATE ? null : new Resampler(rate, 1, TARGET_RATE);
        final int chunk = rate * CHUNK_MS / 1000;
        final int bufferSize = bufferBytes;
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            release();
            throw new IOException("O microfone está em uso por outro aplicativo.");
        }
        running = true;
        thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] in = new short[chunk];
            short[] out = resampler == null ? null : new short[resampler.maxOutput(chunk)];
            while (running) {
                int n = record.read(in, 0, in.length);
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) {
                        Logger.w(TAG, "leitura do microfone falhou: " + n);
                        break;
                    }
                    continue;
                }
                if (muted) {
                    continue;
                }
                if (resampler == null) {
                    sink.onPcm(in, n);
                } else {
                    int m = resampler.process(in, n, out);
                    if (m > 0) {
                        sink.onPcm(out, m);
                    }
                }
            }
        }, "audio-capture");
        thread.start();
        Logger.d(TAG, "microfone aberto a " + rate + " Hz, buffer " + bufferSize + " bytes");
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        release();
    }

    private void release() {
        if (suppressor != null) {
            try {
                suppressor.release();
            } catch (RuntimeException ignored) {
                // effect already gone
            }
            suppressor = null;
        }
        if (record != null) {
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (RuntimeException ignored) {
                // never initialised
            }
            record.release();
            record = null;
        }
    }
}
