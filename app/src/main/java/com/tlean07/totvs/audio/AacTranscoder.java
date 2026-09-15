package com.tlean07.totvs.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.tlean07.totvs.audio.dsp.WavIo;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Compresses the crash-safe WAV a session records into an AAC/M4A file about
 * ten times smaller (48 kbps mono), which is what the review player uses and
 * what is uploaded when the server-side transcription is requested.
 */
public final class AacTranscoder {

    public interface Progress {
        void onProgress(int percent);
    }

    private static final String MIME = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int BIT_RATE = 48_000;
    private static final long TIMEOUT_US = 10_000;

    private AacTranscoder() {
    }

    public static void transcode(File wav, File m4a, Progress progress) throws IOException {
        WavIo.Header header = WavIo.readHeader(wav);
        int sampleRate = header.sampleRate;
        int channels = header.channels;
        long totalFrames = Math.max(1, header.frames());

        MediaFormat format = MediaFormat.createAudioFormat(MIME, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);

        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        int track = -1;
        try (WavIo.Reader reader = new WavIo.Reader(wav)) {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            if (m4a.exists() && !m4a.delete()) {
                throw new IOException("não foi possível substituir " + m4a.getName());
            }
            muxer = new MediaMuxer(m4a.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pcm = new short[2048 * channels];
            long framesFed = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            int lastPct = -1;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int samples = reader.read(pcm);
                        long ptsUs = framesFed * 1_000_000L / sampleRate;
                        if (samples <= 0 || in == null) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            in.clear();
                            int n = Math.min(samples, in.remaining() / 2);
                            for (int i = 0; i < n; i++) {
                                in.putShort(pcm[i]);
                            }
                            codec.queueInputBuffer(inIndex, 0, n * 2, ptsUs, 0);
                            framesFed += n / channels;
                            int pct = (int) Math.min(99, framesFed * 100 / totalFrames);
                            if (pct != lastPct && progress != null) {
                                lastPct = pct;
                                progress.onProgress(pct);
                            }
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxerStarted) {
                            throw new IOException("codec produziu dados antes do formato");
                        }
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(track, out, info);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
            if (progress != null) {
                progress.onProgress(100);
            }
        } catch (RuntimeException e) {
            throw new IOException("falha ao comprimir o áudio: " + e.getMessage(), e);
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // not started
            }
            codec.release();
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                } catch (RuntimeException ignored) {
                    // nothing written
                }
                muxer.release();
            }
        }
    }
}
