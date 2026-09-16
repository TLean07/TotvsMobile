package com.tlean07.totvs.audio;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.tlean07.totvs.audio.asr.VoskModelManager;
import com.tlean07.totvs.audio.diar.DiarizationModels;

import java.util.ArrayList;
import java.util.List;

/**
 * One switch for everything the recorder downloads once: the Portuguese
 * recognition model (Vosk) and the neural speaker-separation models
 * (sherpa-onnx). The UI talks to this class only; it exposes a combined status
 * and progress and downloads the two in sequence.
 */
public final class VoiceModels {

    public enum Status { ABSENT, DOWNLOADING, UNPACKING, READY, ERROR }

    public interface Listener {
        /** Always on the main thread. {@code progress} is 0..100 while downloading, -1 otherwise. */
        void onStatus(Status status, int progress, @Nullable String message);
    }

    public static final int MODEL_SIZE_MB = VoskModelManager.MODEL_SIZE_MB + DiarizationModels.EMBEDDING_SIZE_MB;

    private static VoiceModels instance;

    private final VoskModelManager vosk;
    private final DiarizationModels diar;
    private final List<Listener> listeners = new ArrayList<>();
    private boolean chainDiar;

    public static synchronized VoiceModels get(Context context) {
        if (instance == null) {
            instance = new VoiceModels(context.getApplicationContext());
        }
        return instance;
    }

    private VoiceModels(Context context) {
        vosk = VoskModelManager.get(context);
        diar = DiarizationModels.get(context);
        vosk.addListener((s, p, m) -> {
            if (s == VoskModelManager.Status.READY && chainDiar && !diar.isReady()) {
                chainDiar = false;
                diar.download();
            } else if (s == VoskModelManager.Status.ABSENT || s == VoskModelManager.Status.ERROR) {
                chainDiar = false;
            }
            fire();
        });
        diar.addListener((s, p, m) -> fire());
    }

    public VoskModelManager vosk() {
        return vosk;
    }

    public DiarizationModels diarization() {
        return diar;
    }

    /** Both models installed. */
    public boolean isReady() {
        return vosk.isReady() && diar.isReady();
    }

    public Status status() {
        VoskModelManager.Status v = vosk.status();
        DiarizationModels.Status d = diar.status();
        if (v == VoskModelManager.Status.UNPACKING) {
            return Status.UNPACKING;
        }
        if (v == VoskModelManager.Status.DOWNLOADING || d == DiarizationModels.Status.DOWNLOADING || chainDiar) {
            return Status.DOWNLOADING;
        }
        if (vosk.isReady() && diar.isReady()) {
            return Status.READY;
        }
        if (v == VoskModelManager.Status.ERROR || d == DiarizationModels.Status.ERROR) {
            return Status.ERROR;
        }
        return Status.ABSENT;
    }

    /** Combined 0..100 across the two downloads, -1 when not downloading. */
    public int progress() {
        int total = MODEL_SIZE_MB;
        int done = 0;
        if (vosk.isReady()) {
            done += VoskModelManager.MODEL_SIZE_MB;
        } else if (vosk.status() == VoskModelManager.Status.DOWNLOADING) {
            done += VoskModelManager.MODEL_SIZE_MB * Math.max(0, vosk.progress()) / 100;
        }
        if (diar.isReady()) {
            done += DiarizationModels.EMBEDDING_SIZE_MB;
        } else if (diar.status() == DiarizationModels.Status.DOWNLOADING) {
            done += DiarizationModels.EMBEDDING_SIZE_MB * Math.max(0, diar.progress()) / 100;
        }
        return status() == Status.DOWNLOADING ? Math.min(99, done * 100 / total) : -1;
    }

    @Nullable
    public String message() {
        return vosk.message() != null ? vosk.message() : diar.message();
    }

    @MainThread
    public void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(status(), progress(), message());
    }

    @MainThread
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Downloads whatever is missing: recognition first, then speaker separation. */
    @MainThread
    public void download() {
        if (!vosk.isReady()) {
            chainDiar = !diar.isReady();
            vosk.download();
            fire();
        } else if (!diar.isReady()) {
            diar.download();
        }
    }

    public void cancelDownload() {
        chainDiar = false;
        vosk.cancelDownload();
        diar.cancelDownload();
    }

    private void fire() {
        Status s = status();
        int p = progress();
        String m = message();
        for (Listener l : new ArrayList<>(listeners)) {
            l.onStatus(s, p, m);
        }
    }
}
