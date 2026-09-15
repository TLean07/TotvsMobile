package com.tlean07.totvs.leania;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the class initializer: Android's regex has no UNICODE_CHARACTER_CLASS flag. */
public class LeanIALocalAnalyzerTest {

    @Test
    public void analisaTextoComAcentos() {
        String texto = "[LOCUTOR 1]: Bom dia, sou da TOTVS. Vocês usam o Protheus?\n"
                + "[LOCUTOR 2]: Usamos o SAP, mas está caro e a gente não está satisfeito. "
                + "Temos orçamento de 200 mil por ano.";
        AiAnalysisDTO r = new LeanIALocalAnalyzer().analisar(texto, null);
        assertNotNull(r);
        assertNotNull(r.getResumo());
        assertTrue(r.getResumo().length() > 0);
    }
}
