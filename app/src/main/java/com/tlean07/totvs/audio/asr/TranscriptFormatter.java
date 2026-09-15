package com.tlean07.totvs.audio.asr;

import java.util.List;
import java.util.Locale;

/**
 * Renders turns in the {@code [LOCUTOR N]:} layout the LeanIA analyser parses,
 * so a meeting recorded on the phone goes through the exact same text pipeline
 * as a pasted transcript.
 */
public final class TranscriptFormatter {

    private TranscriptFormatter() {
    }

    /**
     * @param turns   finalised turns (provisional ones are skipped)
     * @param labels  optional speaker names, index = speaker; a null/blank entry falls back to the number
     */
    public static String toLeanIa(List<TranscriptTurn> turns, List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (TranscriptTurn t : turns) {
            if (t.provisional || t.text == null || t.text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("[LOCUTOR ").append(t.speaker + 1).append("]: ").append(capitalise(t.text.trim()));
            if (!t.text.trim().matches(".*[.!?]$")) {
                sb.append('.');
            }
        }
        return sb.toString();
    }

    /** "Pessoa 1: Mariana (cliente); Pessoa 2: Vendedor" – what goes into the "participantes" field. */
    public static String participantes(List<String> names, List<String> roles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("Locutor ").append(i + 1).append(": ");
            String name = names.get(i) == null || names.get(i).isBlank() ? defaultName(i) : names.get(i).trim();
            sb.append(name);
            String role = roles != null && i < roles.size() ? roles.get(i) : null;
            if (role != null && !role.isBlank() && !"OUTRO".equalsIgnoreCase(role) && !name.equalsIgnoreCase(role)) {
                sb.append(" (").append(role.toLowerCase(Locale.ROOT)).append(")");
            }
        }
        return sb.toString();
    }

    public static String defaultName(int speaker) {
        return "Pessoa " + (speaker + 1);
    }

    public static String formatClock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    private static String capitalise(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
