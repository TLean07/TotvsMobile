package com.tlean07.totvs.audio;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.asr.TranscriptTurn;
import com.tlean07.totvs.audio.asr.TranscriptionEngine;
import com.tlean07.totvs.audio.asr.VoskEngine;
import com.tlean07.totvs.audio.asr.VoskModelManager;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.security.AuditLog;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One live meeting recording: microphone → {@link AudioPipeline} → saved
 * {@link Recording}. Owned by {@link MeetingRecorderService} so it survives
 * the screen turning off or the activity being recreated; the activity just
 * attaches a listener to whatever session is current.
 */
public final class RecorderSession {

    public enum State { PREPARING, RECORDING, PAUSED, FINISHING, DONE, ERROR }

    public interface Listener {
        void onState(State state, @Nullable String message);

        void onSnapshot(AudioPipeline.Snapshot snapshot);

        void onLevel(float dbfs, long positionMs);

        void onFinishProgress(int percent);

        /** The on-device LeanIA pass produced (new) signals for the live view. */
        default void onLiveInsights(Recording.AnaliseLocal resumo) {
        }
    }

    private static final String TAG = "RecorderSession";
    private static final int LEVEL_HISTORY = 160;

    private static volatile RecorderSession current;

    private final Context app;
    private final RecordingStore store;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Recording.Marcador> marcadores = new ArrayList<>();
    private final Map<Integer, String> nomes = new HashMap<>();
    private final float[] levelHistory = new float[LEVEL_HISTORY];
    private int levelIndex;

    private File dir;
    private AudioCapture capture;
    private AudioPipeline pipeline;
    private LiveInsights insights;
    private volatile State state = State.PREPARING;
    private volatile String message;
    private volatile AudioPipeline.Snapshot lastSnapshot;
    private volatile Recording.AnaliseLocal lastInsights;
    private volatile Recording result;
    private String modeloAsr;
    private boolean pausedByCall;
    private AudioManager.OnModeChangedListener modeListener;

    @Nullable
    public static RecorderSession current() {
        return current;
    }

    /** Creates and starts the session (idempotent while one is running). */
    public static synchronized RecorderSession start(Context context) {
        RecorderSession s = current;
        if (s != null && s.isActive()) {
            return s;
        }
        s = new RecorderSession(context.getApplicationContext());
        current = s;
        s.begin();
        return s;
    }

    private RecorderSession(Context app) {
        this.app = app;
        this.store = new RecordingStore(app);
        java.util.Arrays.fill(levelHistory, -90f);
    }

    public boolean isActive() {
        return state == State.PREPARING || state == State.RECORDING || state == State.PAUSED || state == State.FINISHING;
    }

    public State state() {
        return state;
    }

    @Nullable
    public String message() {
        return message;
    }

    @Nullable
    public AudioPipeline.Snapshot lastSnapshot() {
        return lastSnapshot;
    }

    @Nullable
    public Recording.AnaliseLocal lastInsights() {
        return lastInsights;
    }

    @Nullable
    public Recording result() {
        return result;
    }

    public boolean hasAsr() {
        return pipeline != null && pipeline.hasAsr();
    }

    public long positionMs() {
        return pipeline == null ? 0 : pipeline.positionMs();
    }

    /** Seconds the recogniser is behind the audio (0 when idle / keeping up). */
    public int asrLagSeconds() {
        AudioPipeline.Snapshot s = lastSnapshot;
        return s == null ? 0 : (int) (s.asrBacklog * 40L / 1000);
    }

    public List<Recording.Marcador> marcadores() {
        return marcadores;
    }

    public Map<Integer, String> nomes() {
        return nomes;
    }

    public boolean wasPausedByCall() {
        return pausedByCall;
    }

    /** Last {@value #LEVEL_HISTORY} levels in dBFS, oldest first (for the waveform after a re-attach). */
    public float[] levelHistory() {
        float[] out = new float[LEVEL_HISTORY];
        synchronized (levelHistory) {
            for (int i = 0; i < LEVEL_HISTORY; i++) {
                out[i] = levelHistory[(levelIndex + i) % LEVEL_HISTORY];
            }
        }
        return out;
    }

    @MainThread
    public void addListener(Listener l) {
        listeners.add(l);
        l.onState(state, message);
        AudioPipeline.Snapshot s = lastSnapshot;
        if (s != null) {
            l.onSnapshot(s);
        }
        Recording.AnaliseLocal i = lastInsights;
        if (i != null) {
            l.onLiveInsights(i);
        }
    }

    @MainThread
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void begin() {
        AppExecutors.io(() -> {
            try {
                dir = store.newDir();
                pipeline = new AudioPipeline(new File(dir, RecordingStore.WAV), pipelineListener);
                VoskModelManager models = VoskModelManager.get(app);
                if (models.isReady()) {
                    try {
                        TranscriptionEngine asr = new VoskEngine(models.load(), VoskModelManager.MODEL_LABEL,
                                pipeline.asrListener());
                        pipeline.attachAsr(asr);
                        modeloAsr = VoskModelManager.MODEL_LABEL;
                        insights = new LiveInsights((resumo, completo) -> {
                            lastInsights = resumo;
                            AppExecutors.main(() -> {
                                for (Listener l : new ArrayList<>(listeners)) {
                                    l.onLiveInsights(resumo);
                                }
                            });
                        });
                    } catch (IOException | RuntimeException e) {
                        Logger.w(TAG, "reconhecimento de voz indisponível: " + e.getMessage());
                    }
                }
                capture = new AudioCapture();
                capture.start((pcm, length) -> {
                    try {
                        pipeline.feed(pcm, length);
                    } catch (IOException e) {
                        fail("Falha ao gravar no armazenamento: " + e.getMessage());
                    }
                });
                registerCallWatcher();
                AuditLog.get(app).record("gravacao.inicio", dir.getName());
                setState(State.RECORDING, null);
            } catch (Exception e) {
                Logger.e(TAG, "não foi possível iniciar a gravação", e);
                cleanupDir();
                fail(e.getMessage() != null ? e.getMessage() : "Não foi possível iniciar a gravação.");
            }
        });
    }

    private final AudioPipeline.Listener pipelineListener = new AudioPipeline.Listener() {
        @Override
        public void onSpeakersRemapped(int[] oldToNew) {
            AppExecutors.main(() -> {
                Map<Integer, String> moved = new HashMap<>();
                for (Map.Entry<Integer, String> e : new ArrayList<>(nomes.entrySet())) {
                    int old = e.getKey();
                    int neu = old >= 0 && old < oldToNew.length ? oldToNew[old] : -1;
                    if (neu >= 0 && !moved.containsKey(neu)) {
                        moved.put(neu, e.getValue());
                    }
                }
                nomes.clear();
                nomes.putAll(moved);
            });
        }

        @Override
        public void onSnapshot(AudioPipeline.Snapshot snapshot) {
            lastSnapshot = snapshot;
            if (insights != null) {
                insights.submit(snapshot.turns);
            }
            AppExecutors.main(() -> {
                for (Listener l : new ArrayList<>(listeners)) {
                    l.onSnapshot(snapshot);
                }
            });
        }

        @Override
        public void onLevel(float dbfs, long positionMs) {
            synchronized (levelHistory) {
                levelHistory[levelIndex] = dbfs;
                levelIndex = (levelIndex + 1) % LEVEL_HISTORY;
            }
            AppExecutors.main(() -> {
                for (Listener l : new ArrayList<>(listeners)) {
                    l.onLevel(dbfs, positionMs);
                }
            });
        }
    };

    @MainThread
    public void pause() {
        if (state != State.RECORDING) {
            return;
        }
        pausedByCall = false;
        capture.setMuted(true);
        AppExecutors.io(() -> pipeline.flush());
        setState(State.PAUSED, null);
    }

    @MainThread
    public void resume() {
        if (state != State.PAUSED) {
            return;
        }
        pausedByCall = false;
        capture.setMuted(false);
        setState(State.RECORDING, null);
    }

    /** Flags the current moment; a null label is filled in later with the words spoken next. */
    @MainThread
    public void addMarker(@Nullable String rotulo) {
        if (pipeline == null) {
            return;
        }
        Recording.Marcador m = new Recording.Marcador();
        m.tMs = pipeline.positionMs();
        m.rotulo = rotulo == null || rotulo.isBlank() ? null : rotulo.trim();
        marcadores.add(m);
    }

    /** Names a detected speaker while the meeting is still running. */
    @MainThread
    public void setSpeakerName(int speaker, @Nullable String name) {
        if (name == null || name.isBlank()) {
            nomes.remove(speaker);
        } else {
            nomes.put(speaker, name.trim());
        }
        AudioPipeline.Snapshot s = lastSnapshot;
        if (s != null) {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onSnapshot(s);
            }
        }
    }

    /** Stops capturing, runs the final passes and saves; the listener gets DONE with {@link #result()}. */
    @MainThread
    public void stop() {
        if (state != State.RECORDING && state != State.PAUSED) {
            return;
        }
        setState(State.FINISHING, null);
        AppExecutors.io(() -> {
            try {
                unregisterCallWatcher();
                capture.stop();
                List<TranscriptTurn> turns = pipeline.finish();
                RecordingFinalizer.Extras extras = new RecordingFinalizer.Extras();
                extras.origem = "microfone";
                extras.modeloAsr = modeloAsr;
                extras.marcadores = new ArrayList<>(marcadores);
                extras.nomes = new HashMap<>(nomes);
                AuthUser user = TotvsApp.get().session().getCurrentUser();
                extras.userId = user == null ? null : user.id;
                extras.insights = insights;
                result = RecordingFinalizer.finish(app, store, dir, pipeline, turns, extras,
                        pct -> AppExecutors.main(() -> {
                            for (Listener l : new ArrayList<>(listeners)) {
                                l.onFinishProgress(pct);
                            }
                        }));
                AuditLog.get(app).record("gravacao.fim", dir.getName() + " · " + (result.duracaoMs / 1000) + "s · "
                        + result.falantes.size() + " pessoas" + (result.criptografada ? " · criptografada" : ""));
                setState(State.DONE, null);
            } catch (Exception e) {
                Logger.e(TAG, "falha ao finalizar a gravação", e);
                fail("Não foi possível salvar a gravação: " + e.getMessage());
            } finally {
                if (insights != null) {
                    insights.close();
                }
                clearCurrent();
            }
        });
    }

    /** Throws the recording away (user cancelled). */
    @MainThread
    public void discard() {
        AppExecutors.io(() -> {
            try {
                unregisterCallWatcher();
                if (capture != null) {
                    capture.stop();
                }
                if (pipeline != null) {
                    pipeline.finish();
                }
            } catch (Exception ignored) {
                // discarding anyway
            }
            if (insights != null) {
                insights.close();
            }
            cleanupDir();
            AuditLog.get(app).record("gravacao.descartada", dir == null ? "-" : dir.getName());
            result = null;
            setState(State.DONE, "descartada");
            clearCurrent();
        });
    }

    private void fail(String msg) {
        try {
            unregisterCallWatcher();
            if (capture != null) {
                capture.stop();
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
        setState(State.ERROR, msg);
        clearCurrent();
    }

    private void cleanupDir() {
        if (dir != null) {
            RecordingStore.deleteRecursively(dir);
        }
    }

    private void clearCurrent() {
        synchronized (RecorderSession.class) {
            if (current == this) {
                current = null;
            }
        }
    }

    private void setState(State s, @Nullable String msg) {
        state = s;
        message = msg;
        AppExecutors.main(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onState(s, msg);
            }
        });
    }

    /** Auto-pauses when a phone call takes over the microphone (Android 12+). */
    private void registerCallWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            modeListener = mode -> {
                boolean inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                        || mode == AudioManager.MODE_RINGTONE;
                if (inCall && state == State.RECORDING) {
                    AppExecutors.main(() -> {
                        pause();
                        pausedByCall = true;
                        setState(State.PAUSED, "Pausado automaticamente por uma chamada.");
                    });
                }
            };
            am.addOnModeChangedListener(AppExecutors::main, modeListener);
        } catch (RuntimeException e) {
            modeListener = null;
        }
    }

    private void unregisterCallWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || modeListener == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            am.removeOnModeChangedListener(modeListener);
        } catch (RuntimeException ignored) {
            // already gone
        }
        modeListener = null;
    }
}
