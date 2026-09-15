package com.tlean07.totvs.audio;

import android.content.Context;

import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.asr.TranscriptWord;
import com.tlean07.totvs.audio.dsp.SpeakerProfile;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingCrypto;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Turns a finished {@link AudioPipeline} into a saved {@link Recording}: writes
 * the metadata first (so nothing is lost if compression fails), swaps the WAV
 * for an M4A, labels markers with what was said right after them, recognises
 * the user's own voice, runs the final on-device LeanIA pass and encrypts the
 * audio at rest.
 */
public final class RecordingFinalizer {

    private static final String TAG = "RecordingFinalizer";
    private static final long MARKER_WINDOW_MS = 6_000;
    private static final int MARKER_MAX_WORDS = 9;

    /** Everything the caller knows about the session that the pipeline does not. */
    public static final class Extras {
        public String origem = "microfone";
        @Nullable
        public String modeloAsr;
        @Nullable
        public String titulo;
        public List<Recording.Marcador> marcadores = new java.util.ArrayList<>();
        public Map<Integer, String> nomes = new java.util.HashMap<>();
        @Nullable
        public String userId;
        @Nullable
        public LiveInsights insights;
    }

    private RecordingFinalizer() {
    }

    public static Recording finish(Context context, RecordingStore store, File dir, AudioPipeline pipeline,
                                   List<TranscriptTurn> turns, Extras extras,
                                   @Nullable AacTranscoder.Progress progress) throws IOException {
        Recording r = new Recording();
        r.id = dir.getName();
        r.criadoEm = System.currentTimeMillis();
        r.duracaoMs = pipeline.positionMs();
        r.origem = extras.origem;
        r.cliente = extras.titulo;
        r.modeloAsr = extras.modeloAsr;
        r.audioArquivo = RecordingStore.WAV;

        int speakers = Math.max(pipeline.speakerCount(), 0);
        long[] talk = pipeline.snapshot().talkTimeMs;
        List<SpeakerProfile> profiles = pipeline.speakerProfiles();
        for (int i = 0; i < speakers; i++) {
            Recording.Falante f = r.falante(i);
            f.tempoFalaMs = i < talk.length ? talk[i] : 0;
            f.nome = extras.nomes.get(i);
            if (i < profiles.size()) {
                f.voz = profiles.get(i).export();
            }
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

        // The user's own voice, learnt on a previous review, becomes "Você".
        int me = new VoicePrints(context).match(extras.userId, profiles);
        if (me >= 0) {
            Recording.Falante f = r.falante(me);
            if (f.nome == null || f.nome.isBlank()) {
                f.nome = "Você";
            }
            f.papel = "VENDEDOR";
        }

        labelMarkers(r, extras.marcadores, pipeline.words());

        if (extras.insights != null && r.temTexto()) {
            r.analiseLocal = extras.insights.analyseNow(turns);
        }
        store.save(r);

        File wav = new File(dir, RecordingStore.WAV);
        File m4a = new File(dir, RecordingStore.M4A);
        RecorderSettings settings = new RecorderSettings(context);
        try {
            AacTranscoder.transcode(wav, m4a, settings.bitrate(), progress);
            if (m4a.length() > 0) {
                r.audioArquivo = RecordingStore.M4A;
                //noinspection ResultOfMethodCallIgnored
                wav.delete();
                if (settings.encryptAtRest()) {
                    try {
                        File enc = new RecordingCrypto(context).encrypt(m4a);
                        r.audioArquivo = enc.getName();
                        r.criptografada = true;
                    } catch (IOException e) {
                        Logger.w(TAG, "criptografia indisponível, mantendo em claro: " + e.getMessage());
                    }
                }
                store.save(r);
            }
        } catch (IOException | RuntimeException e) {
            Logger.w(TAG, "compressão falhou, mantendo WAV: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            m4a.delete();
        }
        return r;
    }

    /** Markers flagged without a label get the words spoken in the seconds after the flag. */
    private static void labelMarkers(Recording r, List<Recording.Marcador> marcadores, List<TranscriptWord> words) {
        if (marcadores == null) {
            return;
        }
        for (Recording.Marcador m : marcadores) {
            Recording.Marcador copy = new Recording.Marcador();
            copy.tMs = m.tMs;
            copy.rotulo = m.rotulo;
            if (copy.rotulo == null || copy.rotulo.isBlank()) {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (TranscriptWord w : words) {
                    if (w.startMs >= m.tMs - 1_000 && w.startMs <= m.tMs + MARKER_WINDOW_MS) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(w.text);
                        if (++n >= MARKER_MAX_WORDS) {
                            break;
                        }
                    }
                }
                copy.rotulo = sb.length() == 0 ? "Momento importante" : "“" + sb + (n >= MARKER_MAX_WORDS ? "…”" : "”");
            }
            r.marcadores.add(copy);
        }
    }
}
