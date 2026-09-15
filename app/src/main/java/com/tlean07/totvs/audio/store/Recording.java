package com.tlean07.totvs.audio.store;

import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.audio.asr.TranscriptTurn;

import java.util.ArrayList;
import java.util.List;

/** A meeting recording kept on the device (serialised to meta.json by Gson). */
public final class Recording {

    public static final String STATUS_RASCUNHO = "RASCUNHO";
    public static final String STATUS_ENVIADA = "ENVIADA";
    public static final String STATUS_FILA = "NA_FILA";

    /** One signal the on-device LeanIA found (risk, opportunity or alert). */
    public static final class Sinal {
        public String tipo;
        public String severidade;
        public String texto;
        public String extra;
        public Double score;
    }

    /** Summary of the on-device LeanIA pass (live during recording, final at the end). */
    public static final class AnaliseLocal {
        public String sentimentoGeral;
        public Double scoreSentimento;
        public String resumo;
        public String proximaAcao;
        public String persona;
        public String budget;
        public Double confiancaVendedor;
        public List<Sinal> riscos = new ArrayList<>();
        public List<Sinal> oportunidades = new ArrayList<>();
        public List<Sinal> alertas = new ArrayList<>();
        public List<String> concorrentes = new ArrayList<>();
        public List<String> produtos = new ArrayList<>();
        public long atualizadoEm;
    }

    public static final class Falante {
        public int indice;
        public String nome;
        /** "VENDEDOR", "CLIENTE" or "OUTRO". */
        public String papel = "OUTRO";
        public long tempoFalaMs;
        /** Voice fingerprint (see SpeakerProfile.export) – lets the app recognise this person next time. */
        public double[] voz;

        public String rotulo() {
            return nome == null || nome.isBlank() ? TranscriptFormatter.defaultName(indice) : nome;
        }
    }

    public static final class Turno {
        public int falante;
        public long inicioMs;
        public long fimMs;
        public String texto;

        public TranscriptTurn toTurn() {
            return new TranscriptTurn(falante, inicioMs, fimMs, texto == null ? "" : texto, false);
        }
    }

    public static final class Marcador {
        public long tMs;
        public String rotulo;
    }

    public String id;
    public long criadoEm;
    public long duracaoMs;
    public String origem = "microfone";
    public String cliente;
    public String vendedor;
    public String participantes;
    public String status = STATUS_RASCUNHO;
    public String transcricaoId;
    public String audioArquivo;
    public String modeloAsr;
    public boolean transcricaoDisponivel;
    public boolean criptografada;
    public AnaliseLocal analiseLocal;
    public List<Falante> falantes = new ArrayList<>();
    public List<Turno> turnos = new ArrayList<>();
    public List<Marcador> marcadores = new ArrayList<>();

    public List<TranscriptTurn> turns() {
        List<TranscriptTurn> out = new ArrayList<>(turnos.size());
        for (Turno t : turnos) {
            out.add(t.toTurn());
        }
        return out;
    }

    public List<String> nomes() {
        List<String> names = new ArrayList<>();
        for (Falante f : falantes) {
            names.add(f.rotulo());
        }
        return names;
    }

    public List<String> papeis() {
        List<String> roles = new ArrayList<>();
        for (Falante f : falantes) {
            roles.add(f.papel == null ? "" : f.papel);
        }
        return roles;
    }

    public Falante falante(int indice) {
        for (Falante f : falantes) {
            if (f.indice == indice) {
                return f;
            }
        }
        Falante f = new Falante();
        f.indice = indice;
        falantes.add(f);
        return f;
    }

    public boolean temTexto() {
        for (Turno t : turnos) {
            if (t.texto != null && !t.texto.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Full transcript in the {@code [LOCUTOR N]:} layout LeanIA parses. */
    public String textoLeanIa() {
        return TranscriptFormatter.toLeanIa(turns(), nomes());
    }

    public String participantesTexto() {
        return TranscriptFormatter.participantes(nomes(), papeis());
    }

    public String tituloOuPadrao() {
        return cliente == null || cliente.isBlank() ? "Reunião sem cliente" : cliente;
    }

    /** Sums the turn durations per speaker (after the user re-attributed turns). */
    public void recalcularTemposDeFala() {
        for (Falante f : falantes) {
            f.tempoFalaMs = 0;
        }
        for (Turno t : turnos) {
            falante(t.falante).tempoFalaMs += Math.max(0, t.fimMs - t.inicioMs);
        }
    }

    /**
     * The user decided two detected voices are the same person: every turn of
     * {@code from} becomes {@code to}, adjacent turns are merged and speakers
     * are renumbered so indices stay contiguous (colours/labels follow).
     */
    public void unirFalantes(int from, int to) {
        if (from == to) {
            return;
        }
        Falante origem = falante(from);
        Falante destino = falante(to);
        destino.tempoFalaMs += origem.tempoFalaMs;
        if ((destino.nome == null || destino.nome.isBlank()) && origem.nome != null) {
            destino.nome = origem.nome;
        }
        if ("OUTRO".equals(destino.papel) && origem.papel != null && !"OUTRO".equals(origem.papel)) {
            destino.papel = origem.papel;
        }
        falantes.remove(origem);
        for (Turno t : turnos) {
            if (t.falante == from) {
                t.falante = to;
            }
        }
        // Merge consecutive turns of the same speaker.
        List<Turno> merged = new ArrayList<>();
        for (Turno t : turnos) {
            Turno last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && last.falante == t.falante) {
                last.fimMs = Math.max(last.fimMs, t.fimMs);
                last.texto = (last.texto == null ? "" : last.texto + " ") + (t.texto == null ? "" : t.texto);
            } else {
                merged.add(t);
            }
        }
        turnos.clear();
        turnos.addAll(merged);
        // Renumber contiguously in the current order of the speaker list.
        java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
        java.util.Collections.sort(falantes, (a, b) -> Integer.compare(a.indice, b.indice));
        for (int i = 0; i < falantes.size(); i++) {
            map.put(falantes.get(i).indice, i);
            falantes.get(i).indice = i;
        }
        for (Turno t : turnos) {
            Integer n = map.get(t.falante);
            t.falante = n == null ? 0 : n;
        }
    }
}
