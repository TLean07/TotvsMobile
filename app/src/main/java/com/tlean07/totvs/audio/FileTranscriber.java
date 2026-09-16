package com.tlean07.totvs.audio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.asr.TranscriptionEngine;
import com.tlean07.totvs.audio.asr.VoskEngine;
import com.tlean07.totvs.audio.asr.VoskModelManager;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs an existing audio file (a meeting recorded with another app, a Teams
 * export…) through the same diarization + recognition pipeline as a live
 * recording and stores the result as a draft {@link Recording}.
 */
public final class FileTranscriber {

    public interface Listener {
        /** Main thread. {@code phase}: 0 decoding/transcribing, 1 compressing, 2 done. */
        void onProgress(int phase, int percent);

        void onDone(Recording recording);

        void onError(String message);
    }

    private static final String TAG = "FileTranscriber";

    private FileTranscriber() {
    }

    public static void run(Context context, Uri uri, @Nullable String displayName, Listener listener) {
        Context app = context.getApplicationContext();
        AppExecutors.io(() -> {
            RecordingStore store = new RecordingStore(app);
            File dir = null;
            try {
                dir = store.newDir();
                AudioPipeline pipeline = new AudioPipeline(new File(dir, RecordingStore.WAV), new AudioPipeline.Listener() {
                    @Override
                    public void onSnapshot(AudioPipeline.Snapshot snapshot) {
                    }

                    @Override
                    public void onLevel(float dbfs, long positionMs) {
                    }
                });
                pipeline.setNeural(com.tlean07.totvs.audio.diar.NeuralDiarizer.ifAvailable(app),
                        pct -> AppExecutors.main(() -> listener.onProgress(1, pct / 2)));
                String modelo = null;
                VoskModelManager models = VoskModelManager.get(app);
                if (models.isReady()) {
                    try {
                        TranscriptionEngine asr = new VoskEngine(models.load(), VoskModelManager.MODEL_LABEL,
                                pipeline.asrListener());
                        pipeline.attachAsr(asr);
                        modelo = VoskModelManager.MODEL_LABEL;
                    } catch (IOException | RuntimeException e) {
                        Logger.w(TAG, "ASR indisponível para importação: " + e.getMessage());
                    }
                }
                AudioFileDecoder.decode(app, uri, dir, pipeline::feed,
                        pct -> AppExecutors.main(() -> listener.onProgress(0, pct)));
                AppExecutors.main(() -> listener.onProgress(1, 0));
                List<TranscriptTurn> turns = pipeline.finish();
                RecordingFinalizer.Extras extras = new RecordingFinalizer.Extras();
                extras.origem = "arquivo";
                extras.modeloAsr = modelo;
                extras.titulo = displayName == null ? null : stripExtension(displayName);
                com.tlean07.totvs.network.dto.AuthUser user = com.tlean07.totvs.TotvsApp.get().session().getCurrentUser();
                extras.userId = user == null ? null : user.id;
                extras.insights = new LiveInsights((a, b) -> { });
                Recording r = RecordingFinalizer.finish(app, store, dir, pipeline, turns, extras,
                        pct -> AppExecutors.main(() -> listener.onProgress(1, pct)));
                extras.insights.close();
                com.tlean07.totvs.security.AuditLog.get(app).record("gravacao.importada", r.id + " · " + extras.titulo);
                AppExecutors.main(() -> {
                    listener.onProgress(2, 100);
                    listener.onDone(r);
                });
            } catch (Exception e) {
                Logger.e(TAG, "importação falhou", e);
                if (dir != null) {
                    RecordingStore.deleteRecursively(dir);
                }
                String msg = e.getMessage() != null ? e.getMessage() : "Não foi possível processar o áudio.";
                AppExecutors.main(() -> listener.onError(msg));
            }
        });
    }

    /**
     * Runs the stored audio of an existing recording through the pipeline again
     * (e.g. the voice model was not installed when it was recorded) and replaces
     * its speakers and transcript in place, keeping the names and roles already
     * typed by their speaker index.
     */
    public static void retranscribe(Context context, Recording existing, Listener listener) {
        Context app = context.getApplicationContext();
        AppExecutors.io(() -> {
            RecordingStore store = new RecordingStore(app);
            File scratch = new File(new File(app.getCacheDir(), "retranscribe"), existing.id);
            try {
                if (!scratch.isDirectory() && !scratch.mkdirs()) {
                    throw new IOException("cache indisponível");
                }
                VoskModelManager models = VoskModelManager.get(app);
                if (!models.isReady()) {
                    throw new IOException("O modelo de voz não está instalado.");
                }
                File audio = store.audioFile(existing);
                if (audio == null || !audio.exists() || audio.length() == 0) {
                    throw new IOException("O áudio desta gravação não foi encontrado.");
                }
                AudioPipeline pipeline = new AudioPipeline(new File(scratch, RecordingStore.WAV), new AudioPipeline.Listener() {
                    @Override
                    public void onSnapshot(AudioPipeline.Snapshot snapshot) {
                    }

                    @Override
                    public void onLevel(float dbfs, long positionMs) {
                    }
                });
                TranscriptionEngine asr = new VoskEngine(models.load(), VoskModelManager.MODEL_LABEL, pipeline.asrListener());
                pipeline.attachAsr(asr);
                pipeline.setNeural(com.tlean07.totvs.audio.diar.NeuralDiarizer.ifAvailable(app),
                        pct -> AppExecutors.main(() -> listener.onProgress(1, pct / 2)));
                AudioFileDecoder.decode(app, Uri.fromFile(audio), scratch, pipeline::feed,
                        pct -> AppExecutors.main(() -> listener.onProgress(0, pct)));
                AppExecutors.main(() -> listener.onProgress(1, 0));
                List<TranscriptTurn> turns = pipeline.finish();
                java.util.Map<Integer, String> nomes = new java.util.HashMap<>();
                java.util.Map<Integer, String> papeis = new java.util.HashMap<>();
                for (Recording.Falante f : existing.falantes) {
                    if (f.nome != null && !f.nome.isBlank()) {
                        nomes.put(f.indice, f.nome);
                    }
                    if (f.papel != null && !f.papel.isBlank()) {
                        papeis.put(f.indice, f.papel);
                    }
                }
                LiveInsights insights = new LiveInsights((a, b) -> { });
                RecordingFinalizer.fillTranscript(existing, pipeline, turns, nomes, papeis, insights);
                insights.close();
                existing.modeloAsr = VoskModelManager.MODEL_LABEL;
                if (existing.duracaoMs <= 0) {
                    existing.duracaoMs = pipeline.positionMs();
                }
                store.save(existing);
                com.tlean07.totvs.security.AuditLog.get(app).record("gravacao.retranscrita", existing.id);
                AppExecutors.main(() -> {
                    listener.onProgress(2, 100);
                    listener.onDone(existing);
                });
            } catch (Exception e) {
                Logger.e(TAG, "nova transcrição falhou", e);
                String msg = e.getMessage() != null ? e.getMessage() : "Não foi possível transcrever o áudio.";
                AppExecutors.main(() -> listener.onError(msg));
            } finally {
                RecordingStore.deleteRecursively(scratch);
            }
        });
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
