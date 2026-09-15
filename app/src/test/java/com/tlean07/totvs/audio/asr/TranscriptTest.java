package com.tlean07.totvs.audio.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tlean07.totvs.audio.VocabularioTotvs;
import com.tlean07.totvs.audio.dsp.SpeakerSegment;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TranscriptTest {

    private static TranscriptWord w(String t, long start, long end) {
        return new TranscriptWord(t, start, end, 1f);
    }

    @Test
    public void wordsAreGroupedIntoTurnsBySpeakerSegment() {
        TranscriptAssembler a = new TranscriptAssembler();
        a.addSegment(new SpeakerSegment(0, 2_000, 0, false));
        a.addSegment(new SpeakerSegment(2_300, 4_000, 1, false));
        a.addWords(Arrays.asList(w("bom", 100, 400), w("dia", 450, 800), w("obrigado", 2_400, 3_000), w("eu", 3_100, 3_300)));
        List<TranscriptTurn> turns = a.turns();
        assertEquals(2, turns.size());
        assertEquals(0, turns.get(0).speaker);
        assertEquals("bom dia", turns.get(0).text);
        assertEquals(1, turns.get(1).speaker);
        assertEquals("obrigado eu", turns.get(1).text);
        assertEquals(2_400, turns.get(1).startMs);
    }

    @Test
    public void wordsInGapsFollowTheNearestSegmentOrThePreviousSpeaker() {
        TranscriptAssembler a = new TranscriptAssembler();
        a.addSegment(new SpeakerSegment(0, 1_000, 0, false));
        a.addSegment(new SpeakerSegment(5_000, 6_000, 1, false));
        // 1.1 s: within 400 ms of segment 0 → speaker 0; 3 s: nowhere near → previous speaker (0)
        a.addWords(Arrays.asList(w("a", 1_050, 1_150), w("b", 2_900, 3_100), w("c", 5_100, 5_200)));
        List<TranscriptTurn> turns = a.turns();
        assertEquals(2, turns.size());
        assertEquals("a b", turns.get(0).text);
        assertEquals(1, turns.get(1).speaker);
    }

    @Test
    public void partialTextBecomesAProvisionalTurnOfTheOpenSpeaker() {
        TranscriptAssembler a = new TranscriptAssembler();
        a.addSegment(new SpeakerSegment(0, 1_000, 0, false));
        a.addWords(Arrays.asList(w("olá", 100, 500)));
        a.setOpenSegment(new SpeakerSegment(1_500, 2_000, 1, true));
        a.setPartial("tudo be", 1_600);
        List<TranscriptTurn> turns = a.turns();
        assertEquals(2, turns.size());
        assertTrue(turns.get(1).provisional);
        assertEquals(1, turns.get(1).speaker);
        assertEquals("tudo be", turns.get(1).text);
        // Finalising the words clears the partial.
        a.addWords(Arrays.asList(w("tudo", 1_600, 1_800), w("bem", 1_850, 2_000)));
        turns = a.turns();
        assertEquals(2, turns.size());
        assertTrue(!turns.get(1).provisional);
        assertEquals("tudo bem", turns.get(1).text);
    }

    @Test
    public void leanIaFormatUsesLocutorTagsAndSkipsProvisional() {
        List<TranscriptTurn> turns = Arrays.asList(
                new TranscriptTurn(0, 0, 1_000, "bom dia, tudo bem", false),
                new TranscriptTurn(1, 1_200, 2_000, "tudo ótimo.", false),
                new TranscriptTurn(0, 2_100, 2_500, "ainda digitando", true));
        String text = TranscriptFormatter.toLeanIa(turns, null);
        assertEquals("[LOCUTOR 1]: Bom dia, tudo bem.\n[LOCUTOR 2]: Tudo ótimo.", text);
    }

    @Test
    public void participantsAndClock() {
        String p = TranscriptFormatter.participantes(Arrays.asList("Mariana", null), Arrays.asList("CLIENTE", "VENDEDOR"));
        assertEquals("Locutor 1: Mariana (cliente); Locutor 2: Pessoa 2 (vendedor)", p);
        assertEquals("00:05", TranscriptFormatter.formatClock(5_400));
        assertEquals("1:02:03", TranscriptFormatter.formatClock(3_723_000));
    }

    @Test
    public void totvsVocabularyIsRestored() {
        assertEquals("hoje vocês usam Protheus no financeiro", VocabularioTotvs.corrigir("hoje vocês usam protege no financeiro"));
        assertEquals("o TOTVS Fluig resolve", VocabularioTotvs.corrigir("o todos os fluidos resolve"));
        assertEquals("usa o Datasul e o Techfin", VocabularioTotvs.corrigir("usa o data sul e o tec fin"));
        assertEquals("a TOTVS é grande", VocabularioTotvs.corrigir("a totes é grande"));
        assertEquals("nada a corrigir", VocabularioTotvs.corrigir("nada a corrigir"));
    }

    @Test
    public void voskJsonParsing() {
        List<TranscriptWord> words = VoskEngine.parseWords(
                "{\"result\":[{\"conf\":0.9,\"end\":1.25,\"start\":0.8,\"word\":\"bom\"},{\"conf\":1.0,\"end\":1.6,\"start\":1.3,\"word\":\"dia\"}],\"text\":\"bom dia\"}");
        assertEquals(2, words.size());
        assertEquals(800, words.get(0).startMs);
        assertEquals(1_250, words.get(0).endMs);
        assertEquals("dia", words.get(1).text);
        assertEquals("bom di", VoskEngine.parsePartial("{\"partial\":\"bom di\"}"));
        assertTrue(VoskEngine.parseWords("{\"text\":\"\"}").isEmpty());
        assertEquals("", VoskEngine.parsePartial("garbage"));
    }
}
