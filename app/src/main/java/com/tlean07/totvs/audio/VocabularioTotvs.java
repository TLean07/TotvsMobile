package com.tlean07.totvs.audio;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processing for the offline recogniser: a small generic acoustic model
 * has never heard "Protheus" or "Fluig", so it writes what it hears
 * ("protege", "fluidos"). These patterns put the TOTVS product names back so
 * LeanIA can match them – the same glossary the analyser uses.
 */
public final class VocabularioTotvs {

    private static final String[][] REGRAS = {
            {"(?i)\\b(pró|pro)\\s*(teus|tese|teu|tews)\\b", "Protheus"},
            {"(?i)\\bprote(ge|gi|us|os|u|ge us)\\b", "Protheus"},
            {"(?i)\\bproteus\\b", "Protheus"},
            {"(?i)\\b(totvs|totes|tótis|totus|totis|tot vs|toques|totz)\\s+(os\\s+)?flu(ig|i|idos|ide|íg|g)\\b", "TOTVS Fluig"},
            {"(?i)\\btodos\\s+os\\s+flu(ig|i|idos|ide|íg|g)\\b", "TOTVS Fluig"},
            {"(?i)\\b(o|a|no|do)\\s+flu(idos|ide|íg|ig)\\b", "$1 Fluig"},
            {"(?i)\\bflu\\s*ig\\b", "Fluig"},
            {"(?i)\\bdata\\s*sul\\b", "Datasul"},
            {"(?i)\\bdatas\\s+sul\\b", "Datasul"},
            {"(?i)\\btec(h)?\\s*fin\\b", "Techfin"},
            {"(?i)\\b(totes|tótis|totus|totis|tot vs|totz|tóquios|totves)\\b", "TOTVS"},
            {"(?i)\\btotvs\\b", "TOTVS"},
            {"(?i)\\bt\\s*o\\s*t\\s*v\\s*s\\b", "TOTVS"},
            {"(?i)\\berre\\s+eme\\b", "RM"},
            {"(?i)\\bsap\\b", "SAP"},
            {"(?i)\\boráculo\\b", "Oracle"},
    };

    private static final Pattern[] PADROES = new Pattern[REGRAS.length];

    static {
        for (int i = 0; i < REGRAS.length; i++) {
            PADROES[i] = Pattern.compile(REGRAS[i][0]);
        }
    }

    private VocabularioTotvs() {
    }

    public static String corrigir(String texto) {
        if (texto == null || texto.isEmpty()) {
            return texto;
        }
        String out = texto;
        for (int i = 0; i < PADROES.length; i++) {
            Matcher m = PADROES[i].matcher(out);
            if (m.find()) {
                out = m.replaceAll(REGRAS[i][1]);
            }
        }
        return out;
    }
}
