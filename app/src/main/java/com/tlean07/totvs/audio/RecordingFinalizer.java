package com.tlean07.totvs.audio;

import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Turns a finished {@link AudioPipeline} into a saved {@link Recording}: writes
 * the metadata first (so nothing is lost if compression fails) and then swaps
 * the WAV for an M4A.
 */
public final class RecordingFinalizer {

    private static final String TAG = "RecordingFinalizer";

    private RecordingFinalizer() {
    }

    public static Recording finish(RecordingStore store, File dir, AudioPipeline pipeline, List<TranscriptTurn> turns,
                                   String origem, @Nullable String modeloAsr, List<Recording.Marcador> marcadores,
                                   @Nullable String titulo, @Nullable AacTranscoder.Progress progress) throws IOException {
        Recording r = new Recording();
        r.id = dir.getName();
        r.criadoEm = System.currentTimeMillis();
        r.duracaoMs = pipeline.positionMs();
        r.origem = origem;
        r.cliente = titulo;
        r.modeloAsr = modeloAsr;
        r.audioArquivo = RecordingStore.WAV;
        if (marcadores != null) {
            r.marcadores.addAll(marcadores);
        }
        int speakers = Math.max(pipeline.speakerCount(), 0);
        long[] talk = pipeline.snapshot().talkTimeMs;
        for (int i = 0; i < speakers; i++) {
            Recording.Falante f = r.falante(i);
            f.tempoFalaMs = i < talk.length ? talk[i] : 0;
        }
        for (TranscriptTurn t : turns) {
            if (t.provisional || t.text == null || t.text.isBlank()) {
                continue;
            }
            Recording.Turno turno = new Recording.Turno();
            turno.falante = Math.max(0, t.speaker);
            turno.inicioMs = t.startMs;
            turno.fimMs = t.endMs;
            turno.texto = t.text;
            r.turnos.add(turno);
            r.falante(turno.falante);
        }
        r.transcricaoDisponivel = pipeline.hasAsr() && r.temTexto();
        store.save(r);

        File wav = new File(dir, RecordingStore.WAV);
        File m4a = new File(dir, RecordingStore.M4A);
        try {
            AacTranscoder.transcode(wav, m4a, progress);
            if (m4a.length() > 0) {
                r.audioArquivo = RecordingStore.M4A;
                store.save(r);
                //noinspection ResultOfMethodCallIgnored
                wav.delete();
            }
        } catch (IOException | RuntimeException e) {
            Logger.w(TAG, "compressão falhou, mantendo WAV: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            m4a.delete();
        }
        return r;
    }
}
