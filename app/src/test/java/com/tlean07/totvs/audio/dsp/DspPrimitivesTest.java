package com.tlean07.totvs.audio.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Random;

public class DspPrimitivesTest {

    @Test
    public void fftMatchesNaiveDft() {
        int n = 512;
        Fft fft = new Fft(n);
        float[] frame = new float[n];
        Random r = new Random(7);
        for (int i = 0; i < n; i++) {
            frame[i] = (float) (r.nextGaussian() * 0.3 + Math.sin(2 * Math.PI * 37 * i / n));
        }
        float[] power = new float[n / 2 + 1];
        fft.powerSpectrum(frame, power);
        for (int k = 0; k <= n / 2; k += 17) {
            double re = 0;
            double im = 0;
            for (int i = 0; i < n; i++) {
                re += frame[i] * Math.cos(2 * Math.PI * k * i / n);
                im -= frame[i] * Math.sin(2 * Math.PI * k * i / n);
            }
            double expected = re * re + im * im;
            assertEquals("bin " + k, expected, power[k], Math.max(1e-2, expected * 1e-3));
        }
    }

    @Test
    public void featureExtractorTracksPitchOfAHarmonicTone() {
        final float[] seen = new float[1];
        final int[] frames = new int[1];
        FeatureExtractor fe = new FeatureExtractor(f -> {
            if (f.voiced()) {
                seen[0] += f.pitchHz;
                frames[0]++;
            }
        });
        short[] audio = new short[16_000];
        double phase = 0;
        for (int i = 0; i < audio.length; i++) {
            phase += 2 * Math.PI * 150 / 16_000;
            double s = 0;
            for (int h = 1; h <= 8; h++) {
                s += Math.sin(h * phase) / h;
            }
            audio[i] = (short) (s * 8000);
        }
        fe.process(audio, audio.length);
        assertTrue("most frames should be voiced", frames[0] > 40);
        assertEquals(150f, seen[0] / frames[0], 6f);
    }

    @Test
    public void vadNeedsOnsetAndHoldsThroughShortGaps() {
        VoiceActivityDetector vad = new VoiceActivityDetector(300);
        for (int i = 0; i < 50; i++) {
            assertFalse(vad.update(-70f));
        }
        assertFalse(vad.update(-20f));
        assertFalse(vad.update(-20f));
        assertTrue("third loud frame declares speech", vad.update(-20f));
        // 10 quiet frames (160 ms) is shorter than the 300 ms hangover.
        for (int i = 0; i < 10; i++) {
            assertTrue(vad.update(-70f));
        }
        assertTrue(vad.update(-20f));
        // 20 quiet frames (320 ms) ends it.
        boolean speech = true;
        for (int i = 0; i < 20; i++) {
            speech = vad.update(-70f);
        }
        assertFalse(speech);
    }

    @Test
    public void vadIgnoresDigitalSilenceNoise() {
        VoiceActivityDetector vad = new VoiceActivityDetector(300);
        for (int i = 0; i < 100; i++) {
            assertFalse(vad.update(-85f + (i % 3)));
        }
        // -60 dBFS is "louder" than the floor by 25 dB but still below the absolute minimum.
        for (int i = 0; i < 10; i++) {
            assertFalse(vad.update(-60f));
        }
    }

    @Test
    public void resamplerKeepsFrequencyAndLength() {
        int inRate = 48_000;
        short[] in = new short[inRate];
        for (int i = 0; i < in.length; i++) {
            in[i] = (short) (Math.sin(2 * Math.PI * 1_000 * i / inRate) * 10_000);
        }
        Resampler rs = new Resampler(inRate, 1, 16_000);
        short[] out = new short[rs.maxOutput(in.length)];
        int n = rs.process(in, in.length, out);
        assertEquals(16_000, n, 3);
        int crossings = 0;
        for (int i = 1; i < n; i++) {
            if ((out[i - 1] < 0) != (out[i] < 0)) {
                crossings++;
            }
        }
        // 1 kHz over one second = 2000 zero crossings (minus the filter's warm-up).
        assertEquals(2_000, crossings, 40);
        int peak = 0;
        for (int i = 2_000; i < n; i++) {
            peak = Math.max(peak, Math.abs(out[i]));
        }
        assertTrue("amplitude preserved, got " + peak, peak > 8_500 && peak < 11_000);
    }

    @Test
    public void resamplerDownmixesStereo() {
        short[] in = {1000, 3000, -2000, 2000, 500, 500, 0, 0};
        Resampler rs = new Resampler(16_000, 2, 16_000);
        short[] out = new short[rs.maxOutput(4)];
        int n = rs.process(in, in.length, out);
        assertEquals(4, n);
        // one sample of latency: out[i] is the downmix of frame i-1
        assertEquals(0, out[0], 1);
        assertEquals(2000, out[1], 1);
        assertEquals(0, out[2], 1);
        assertEquals(500, out[3], 1);
    }

    @Test
    public void wavRoundTripAndRepair() throws Exception {
        File f = File.createTempFile("test", ".wav");
        f.deleteOnExit();
        short[] pcm = new short[1600];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 13 - 9000);
        }
        try (WavIo.Writer w = new WavIo.Writer(f, 16_000, 1)) {
            w.write(pcm, pcm.length);
            assertEquals(100, w.durationMs());
        }
        WavIo.Header h = WavIo.readHeader(f);
        assertEquals(16_000, h.sampleRate);
        assertEquals(1, h.channels);
        assertEquals(1600, h.frames());
        try (WavIo.Reader r = new WavIo.Reader(f)) {
            short[] buf = new short[4096];
            int n = r.read(buf);
            assertEquals(1600, n);
            assertEquals(pcm[1599], buf[1599]);
            assertEquals(-1, r.read(buf));
        }
        // Simulate a crash: zero the sizes in the header, then repair from the file length.
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.seek(40);
            raf.writeInt(0);
        }
        WavIo.Header repaired = WavIo.repair(f);
        assertEquals(1600, repaired.frames());
    }

    @Test
    public void clustererRefusesToFoundASpeakerFromAShortUtterance() {
        SpeakerClusterer c = new SpeakerClusterer();
        SpeakerProfile a = new SpeakerProfile();
        FrameFeatures f = new FrameFeatures();
        f.pitchHz = 120f;
        f.mfcc[1] = 5f;
        for (int i = 0; i < 100; i++) {
            a.add(f);
        }
        assertTrue(c.assign(a).created);
        SpeakerProfile b = new SpeakerProfile();
        FrameFeatures g = new FrameFeatures();
        g.pitchHz = 240f;
        g.mfcc[1] = -5f;
        for (int i = 0; i < 10; i++) {
            b.add(g);
        }
        SpeakerClusterer.Decision d = c.assign(b);
        assertFalse("10 voiced frames are not enough evidence", d.created);
        assertEquals(0, d.speaker);
        for (int i = 0; i < 100; i++) {
            b.add(g);
        }
        assertTrue("with evidence the different voice founds speaker 2", c.assign(b).created);
        assertEquals(2, c.speakerCount());
    }
}
