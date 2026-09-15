package com.tlean07.totvs.audio.dsp;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal RIFF/WAVE support: a crash-safe writer (header patched on close, and
 * repairable from the file length if the process dies) and a reader for PCM16 files.
 */
public final class WavIo {

    private WavIo() {
    }

    public static final class Header {
        public int sampleRate;
        public int channels;
        public int bitsPerSample;
        public long dataOffset;
        public long dataBytes;

        public long frames() {
            return dataBytes / (channels * (bitsPerSample / 8));
        }

        public long durationMs() {
            return sampleRate == 0 ? 0 : frames() * 1000L / sampleRate;
        }
    }

    /** Streams PCM16 into a WAV file; the sizes in the header are fixed up on {@link #close()}. */
    public static final class Writer implements Closeable {
        private final File file;
        private final int sampleRate;
        private final int channels;
        private final OutputStream out;
        private final byte[] scratch = new byte[8192];
        private long dataBytes;
        private boolean closed;

        public Writer(File file, int sampleRate, int channels) throws IOException {
            this.file = file;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024);
            out.write(header(sampleRate, channels, 0));
        }

        public void write(short[] pcm, int length) throws IOException {
            int i = 0;
            while (i < length) {
                int n = Math.min(scratch.length / 2, length - i);
                for (int j = 0; j < n; j++) {
                    short s = pcm[i + j];
                    scratch[2 * j] = (byte) (s & 0xff);
                    scratch[2 * j + 1] = (byte) ((s >> 8) & 0xff);
                }
                out.write(scratch, 0, n * 2);
                dataBytes += n * 2L;
                i += n;
            }
        }

        public long dataBytes() {
            return dataBytes;
        }

        public long durationMs() {
            return dataBytes / (2L * channels) * 1000L / sampleRate;
        }

        /** Pushes buffered samples to disk (call periodically so a crash loses little). */
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            out.flush();
            out.close();
            patchHeader(file, sampleRate, channels, dataBytes);
        }
    }

    /** Rewrites the RIFF/data sizes from the real file length (recovery after a crash). */
    public static Header repair(File file) throws IOException {
        Header h = readHeader(file);
        long real = file.length() - h.dataOffset;
        if (real < 0) {
            throw new IOException("truncated wav");
        }
        patchHeader(file, h.sampleRate, h.channels, real);
        h.dataBytes = real;
        return h;
    }

    public static Header readHeader(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] chunk = new byte[12];
            raf.readFully(chunk);
            if (!"RIFF".equals(new String(chunk, 0, 4, "US-ASCII")) || !"WAVE".equals(new String(chunk, 8, 4, "US-ASCII"))) {
                throw new IOException("not a wav file");
            }
            Header h = new Header();
            byte[] hdr = new byte[8];
            while (true) {
                if (raf.read(hdr) < 8) {
                    throw new EOFException("no data chunk");
                }
                String id = new String(hdr, 0, 4, "US-ASCII");
                long size = le32(hdr, 4) & 0xffffffffL;
                if ("fmt ".equals(id)) {
                    byte[] fmt = new byte[(int) size];
                    raf.readFully(fmt);
                    int format = le16(fmt, 0);
                    h.channels = le16(fmt, 2);
                    h.sampleRate = le32(fmt, 4);
                    h.bitsPerSample = le16(fmt, 14);
                    if (format != 1 || h.bitsPerSample != 16) {
                        throw new IOException("only PCM16 wav is supported");
                    }
                } else if ("data".equals(id)) {
                    h.dataOffset = raf.getFilePointer();
                    h.dataBytes = size;
                    if (h.dataBytes == 0 || h.dataBytes == 0xffffffffL || h.dataOffset + h.dataBytes > file.length()) {
                        h.dataBytes = file.length() - h.dataOffset;
                    }
                    return h;
                } else {
                    raf.seek(raf.getFilePointer() + size + (size & 1));
                }
            }
        }
    }

    /** Sequential PCM16 reader over the data chunk. */
    public static final class Reader implements Closeable {
        public final Header header;
        private final InputStream in;
        private long remaining;
        private final byte[] buffer = new byte[8192];

        public Reader(File file) throws IOException {
            header = readHeader(file);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(header.dataOffset);
            this.in = new java.io.FileInputStream(raf.getFD()) {
                @Override
                public void close() throws IOException {
                    raf.close();
                }
            };
            remaining = header.dataBytes;
        }

        /** Reads up to {@code pcm.length} interleaved samples; returns count or -1 at EOF. */
        public int read(short[] pcm) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int want = (int) Math.min((long) pcm.length * 2, Math.min(remaining, buffer.length));
            want -= want & 1;
            int got = 0;
            while (got < want) {
                int n = in.read(buffer, got, want - got);
                if (n < 0) {
                    break;
                }
                got += n;
            }
            if (got == 0) {
                return -1;
            }
            remaining -= got;
            int samples = got / 2;
            for (int i = 0; i < samples; i++) {
                pcm[i] = (short) ((buffer[2 * i] & 0xff) | (buffer[2 * i + 1] << 8));
            }
            return samples;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static byte[] header(int sampleRate, int channels, long dataBytes) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int blockAlign = channels * 2;
        b.put("RIFF".getBytes()).putInt((int) Math.min(Integer.MAX_VALUE, 36 + dataBytes)).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * blockAlign).putShort((short) blockAlign).putShort((short) 16);
        b.put("data".getBytes()).putInt((int) Math.min(Integer.MAX_VALUE, dataBytes));
        return b.array();
    }

    private static void patchHeader(File file, int sampleRate, int channels, long dataBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.write(header(sampleRate, channels, dataBytes));
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
