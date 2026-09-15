package com.tlean07.totvs.audio.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SpeakerDiarizerTest {

    private static final SyntheticVoice LOW = new SyntheticVoice(118f, 520f, 1_500f, 1);
    private static final SyntheticVoice HIGH = new SyntheticVoice(215f, 800f, 2_400f, 2);

    private static class Capture implements SpeakerDiarizer.Listener {
        final List<SpeakerSegment> segments = new ArrayList<>();
        int created;

        @Override
        public void onSpeechStart(long tMs) {
        }

        @Override
        public void onProvisional(SpeakerSegment segment) {
        }

        @Override
        public void onSegment(SpeakerSegment segment, boolean wasCreated) {
            segments.add(segment);
            if (wasCreated) {
                created++;
            }
        }

        @Override
        public void onSpeechEnd(long tMs) {
        }
    }

    private static void feed(SpeakerDiarizer d, short[] audio, int length) {
        short[] chunk = new short[640];
        for (int i = 0; i < length; i += chunk.length) {
            int n = Math.min(chunk.length, length - i);
            System.arraycopy(audio, i, chunk, 0, n);
            d.process(chunk, n);
        }
        d.flush();
    }

    @Test
    public void alternatingVoicesYieldTwoSpeakersWithAlternatingLabels() {
        short[] audio = new short[16_000 * 40];
        int pos = SyntheticVoice.silence(audio, 0, 0.5);
        List<Integer> truth = new ArrayList<>();
        for (int turn = 0; turn < 6; turn++) {
            SyntheticVoice v = turn % 2 == 0 ? LOW : HIGH;
            pos += v.speak(audio, pos, 2.2);
            truth.add(turn % 2);
            pos += SyntheticVoice.silence(audio, pos, 0.7);
        }
        Capture c = new Capture();
        SpeakerDiarizer d = new SpeakerDiarizer(c);
        feed(d, audio, pos);

        assertEquals("two voices", 2, d.speakerCount());
        assertEquals(6, c.segments.size());
        for (int i = 0; i < 6; i++) {
            assertEquals("turn " + i, truth.get(i).intValue(), c.segments.get(i).speaker);
        }
        // Timestamps line up with where the turns actually are (0.5 s lead-in, 2.9 s per turn).
        assertTrue(Math.abs(c.segments.get(1).startMs - 3_400) < 200);
    }

    @Test
    public void sameVoiceRepeatedStaysOneSpeaker() {
        short[] audio = new short[16_000 * 20];
        int pos = SyntheticVoice.silence(audio, 0, 0.5);
        for (int turn = 0; turn < 4; turn++) {
            pos += LOW.speak(audio, pos, 1.8);
            pos += SyntheticVoice.silence(audio, pos, 0.6);
        }
        Capture c = new Capture();
        SpeakerDiarizer d = new SpeakerDiarizer(c);
        feed(d, audio, pos);
        assertEquals(1, d.speakerCount());
        assertEquals(4, c.segments.size());
    }

    @Test
    public void voiceChangeWithoutPauseIsSplit() {
        short[] audio = new short[16_000 * 20];
        int pos = SyntheticVoice.silence(audio, 0, 0.5);
        pos += LOW.speak(audio, pos, 2.0);
        pos += SyntheticVoice.silence(audio, pos, 0.6);
        pos += HIGH.speak(audio, pos, 2.0);
        pos += SyntheticVoice.silence(audio, pos, 0.6);
        // Now LOW talks and HIGH cuts in with no gap at all.
        int changeAt = pos + (int) (3.0 * 16_000);
        pos += LOW.speak(audio, pos, 3.0);
        pos += HIGH.speak(audio, pos, 3.0);
        pos += SyntheticVoice.silence(audio, pos, 0.6);
        Capture c = new Capture();
        SpeakerDiarizer d = new SpeakerDiarizer(c);
        feed(d, audio, pos);

        assertEquals(2, d.speakerCount());
        long changeMs = changeAt * 1000L / 16_000;
        boolean found = false;
        for (SpeakerSegment s : c.segments) {
            if (s.speaker == 1 && Math.abs(s.startMs - changeMs) < 900) {
                found = true;
            }
        }
        assertTrue("a segment of the second voice should start near the change point", found);
    }

    @Test
    public void silenceProducesNothing() {
        short[] audio = new short[16_000 * 5];
        int pos = SyntheticVoice.silence(audio, 0, 5.0);
        Capture c = new Capture();
        SpeakerDiarizer d = new SpeakerDiarizer(c);
        feed(d, audio, pos);
        assertEquals(0, d.speakerCount());
        assertTrue(c.segments.isEmpty());
    }

    @Test
    public void refineRelabelsEarlyMistakesAndRenumbersByAppearance() {
        short[] audio = new short[16_000 * 40];
        int pos = SyntheticVoice.silence(audio, 0, 0.5);
        for (int turn = 0; turn < 6; turn++) {
            SyntheticVoice v = turn % 2 == 0 ? HIGH : LOW;
            pos += v.speak(audio, pos, 2.0);
            pos += SyntheticVoice.silence(audio, pos, 0.7);
        }
        Capture c = new Capture();
        SpeakerDiarizer d = new SpeakerDiarizer(c);
        feed(d, audio, pos);
        List<SpeakerSegment> refined = d.refine();
        assertEquals(2, d.speakerCount());
        assertEquals(0, refined.get(0).speaker);
        assertEquals(1, refined.get(1).speaker);
        assertEquals(0, refined.get(2).speaker);
    }
}
