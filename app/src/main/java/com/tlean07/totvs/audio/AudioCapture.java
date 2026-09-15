package com.tlean07.totvs.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import com.tlean07.totvs.audio.dsp.FeatureExtractor;
import com.tlean07.totvs.audio.dsp.Resampler;
import com.tlean07.totvs.audio.dsp.WavIo;

import androidx.annotation.Nullable;

import java.io.File;
import com.tlean07.totvs.util.Logger;

import java.io.IOException;

/**
 * Microphone capture for a meeting: the phone lies on the table and several
 * people talk at different distances, so the audio must come from the raw
 * microphone path, not the phone-call one.
 *
 * <ul>
 *   <li>Source {@code UNPROCESSED} when the device supports it, else {@code MIC};
 *       {@code VOICE_RECOGNITION} only as a last resort. The voice-call sources
 *       run tuned noise/echo processing that treats anybody who is not the
 *       nearest talker as background and squashes them.</li>
 *   <li>No platform NoiseSuppressor for the same reason; AGC disabled when the
 *       device exposes it, so a loud near talker does not pump the level of the
 *       quiet far one.</li>
 *   <li>The microphone is opened at its native rate (48/44.1 kHz) and that
 *       stream is written to the WAV as is, so the saved audio keeps its full
 *       quality; a resampled 16 kHz copy feeds the recogniser and the voice
 *       separation.</li>
 * </ul>
 */
public final class AudioCapture {

    public interface Sink {
        /** Called on the capture thread with 16 kHz mono samples. */
        void onPcm(short[] pcm, int length);
    }

    private static final String TAG = "AudioCapture";
    private static final int TARGET_RATE = FeatureExtractor.SAMPLE_RATE;
    /** Native rates first: the 16 kHz path on many phones is the narrowband voice path. */
    private static final int[] CANDIDATE_RATES = {48_000, 44_100, TARGET_RATE, 32_000, 22_050};
    /** 40 ms of audio per read. */
    private static final int CHUNK_MS = 40;

    private AudioRecord record;
    private android.media.audiofx.AutomaticGainControl agc;
    private WavIo.Writer nativeWav;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean muted;
    private int sampleRate;
    private String sourceName = "?";

    public int sampleRate() {
        return sampleRate;
    }

    /** Which microphone path was opened (for diagnostics). */
    public String sourceName() {
        return sourceName;
    }

    /** True while the sink receives silence-free audio; {@link #setMuted} drops chunks during a pause. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public void start(Sink sink) throws IOException {
        start(null, sink);
    }

    /**
     * @param nativeWav when given, every captured chunk is also written here at the
     *                  microphone's native rate (the file the recording keeps)
     */
    @SuppressLint("MissingPermission")
    public void start(@Nullable File nativeWav, Sink sink) throws IOException {
        if (running) {
            return;
        }
        AudioRecord opened = null;
        int rate = 0;
        int bufferBytes = 0;
        outer:
        for (int source : candidateSources()) {
            for (int candidate : CANDIDATE_RATES) {
                int min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) {
                    continue;
                }
                int wanted = Math.max(min, candidate * 2 /* bytes */ * 500 / 1000 /* 0.5 s */);
                try {
                    AudioRecord r = new AudioRecord(source, candidate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, wanted);
                    if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                        opened = r;
                        rate = candidate;
                        bufferBytes = wanted;
                        sourceName = sourceName(source);
                        break outer;
                    }
                    r.release();
                } catch (IllegalArgumentException e) {
                    Logger.w(TAG, "fonte " + sourceName(source) + " a " + candidate + " Hz recusada");
                }
            }
        }
        if (opened == null) {
            throw new IOException("O microfone não pôde ser aberto.");
        }
        record = opened;
        sampleRate = rate;
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                agc = android.media.audiofx.AutomaticGainControl.create(record.getAudioSessionId());
                if (agc != null) {
                    agc.setEnabled(false);
                }
            }
        } catch (RuntimeException e) {
            agc = null;
        }
        final Resampler resampler = rate == TARGET_RATE ? null : new Resampler(rate, 1, TARGET_RATE);
        final int chunk = rate * CHUNK_MS / 1000;
        final int bufferSize = bufferBytes;
        if (nativeWav != null) {
            this.nativeWav = new WavIo.Writer(nativeWav, rate, 1);
        }
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
            long lastFlush = System.currentTimeMillis();
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
                WavIo.Writer w = this.nativeWav;
                if (w != null) {
                    try {
                        w.write(in, n);
                        long now = System.currentTimeMillis();
                        if (now - lastFlush > 2_000) {
                            lastFlush = now;
                            w.flush();
                        }
                    } catch (IOException e) {
                        Logger.w(TAG, "falha ao gravar o WAV: " + e.getMessage());
                    }
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
        Logger.d(TAG, "microfone " + sourceName + " a " + rate + " Hz, buffer " + bufferSize + " bytes");
    }

    private static int[] candidateSources() {
        java.util.List<Integer> sources = new java.util.ArrayList<>();
        if (unprocessedSupported()) {
            sources.add(MediaRecorder.AudioSource.UNPROCESSED);
        }
        sources.add(MediaRecorder.AudioSource.MIC);
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        int[] out = new int[sources.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = sources.get(i);
        }
        return out;
    }

    private static boolean unprocessedSupported() {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                    com.tlean07.totvs.TotvsApp.get().getSystemService(android.content.Context.AUDIO_SERVICE);
            return am != null && "true".equals(am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.UNPROCESSED:
                return "UNPROCESSED";
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            default:
                return String.valueOf(source);
        }
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
        if (agc != null) {
            try {
                agc.release();
            } catch (RuntimeException ignored) {
                // effect already gone
            }
            agc = null;
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
        WavIo.Writer w = nativeWav;
        nativeWav = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException e) {
                Logger.w(TAG, "falha ao fechar o WAV: " + e.getMessage());
            }
        }
    }
}
