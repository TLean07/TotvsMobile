package com.tlean07.totvs.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.tlean07.totvs.audio.dsp.FeatureExtractor;
import com.tlean07.totvs.audio.dsp.Resampler;
import com.tlean07.totvs.audio.dsp.WavIo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Turns any audio the phone can play (WAV, M4A/AAC, MP3, OGG, FLAC…) into the
 * 16 kHz mono PCM16 stream the pipeline expects, so an existing meeting
 * recording can be transcribed and diarized exactly like a live one.
 */
public final class AudioFileDecoder {

    public interface Sink {
        void onPcm(short[] pcm, int length) throws IOException;
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private static final long TIMEOUT_US = 10_000;

    private AudioFileDecoder() {
    }

    public static void decode(Context context, Uri uri, File scratchDir, Sink sink, Progress progress) throws IOException {
        File local = copyToLocal(context, uri, scratchDir);
        try {
            if (isWav(local)) {
                decodeWav(local, sink, progress);
            } else {
                decodeCompressed(local, sink, progress);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            local.delete();
        }
    }

    private static File copyToLocal(Context context, Uri uri, File dir) throws IOException {
        File out = new File(dir, "import.src");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("arquivo inacessível");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
            }
        }
        return out;
    }

    private static boolean isWav(File f) {
        try {
            WavIo.readHeader(f);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void decodeWav(File f, Sink sink, Progress progress) throws IOException {
        try (WavIo.Reader r = new WavIo.Reader(f)) {
            Resampler rs = new Resampler(r.header.sampleRate, r.header.channels, FeatureExtractor.SAMPLE_RATE);
            short[] in = new short[4096];
            short[] out = new short[rs.maxOutput(4096)];
            long total = Math.max(1, r.header.dataBytes);
            long done = 0;
            int n;
            while ((n = r.read(in)) > 0) {
                done += n * 2L;
                if (rs.isIdentity()) {
                    sink.onPcm(in, n);
                } else {
                    int m = rs.process(in, n, out);
                    if (m > 0) {
                        sink.onPcm(out, m);
                    }
                }
                if (progress != null) {
                    progress.onProgress((int) Math.min(99, done * 100 / total));
                }
            }
        }
    }

    private static void decodeCompressed(File f, Sink sink, Progress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(f.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = fmt;
                    break;
                }
            }
            if (format == null) {
                throw new IOException("O arquivo não contém uma faixa de áudio.");
            }
            extractor.selectTrack(trackIndex);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            Resampler rs = new Resampler(sampleRate, channels, FeatureExtractor.SAMPLE_RATE);
            short[] tmp = new short[8192];
            short[] out = new short[rs.maxOutput(8192 / channels) + 16];

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int lastPct = -1;
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        int size = in == null ? -1 : extractor.readSampleData(in, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0);
                            extractor.advance();
                            if (durationUs > 0 && progress != null) {
                                int pct = (int) Math.min(99, pts * 100 / durationUs);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    progress.onProgress(pct);
                                }
                            }
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int newRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    if (newRate != sampleRate || newChannels != channels) {
                        sampleRate = newRate;
                        channels = newChannels;
                        rs = new Resampler(sampleRate, channels, FeatureExtractor.SAMPLE_RATE);
                        out = new short[rs.maxOutput(8192 / channels) + 16];
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(outIndex);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.order(ByteOrder.nativeOrder());
                        int samples;
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            samples = info.size / 4;
                            if (tmp.length < samples) {
                                tmp = new short[samples];
                                out = new short[rs.maxOutput(samples / channels) + 16];
                            }
                            for (int i = 0; i < samples; i++) {
                                float v = buf.getFloat();
                                tmp[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
                            }
                        } else {
                            samples = info.size / 2;
                            if (tmp.length < samples) {
                                tmp = new short[samples];
                                out = new short[rs.maxOutput(samples / channels) + 16];
                            }
                            buf.asShortBuffer().get(tmp, 0, samples);
                        }
                        int m = rs.process(tmp, samples, out);
                        if (m > 0) {
                            sink.onPcm(out, m);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Não foi possível decodificar o áudio (" + e.getClass().getSimpleName() + ").", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // not started
                }
                codec.release();
            }
            extractor.release();
        }
    }
}
