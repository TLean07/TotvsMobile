package com.tlean07.totvs.audio.diar;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The two neural models behind speaker separation (sherpa-onnx):
 * <ul>
 *   <li>pyannote segmentation 3.0 (6 MB) – ships inside the APK as an asset and
 *       is copied to the private files once;</li>
 *   <li>a speaker-embedding model (~26 MB) – downloaded once from the sherpa-onnx
 *       release page, like the Vosk model.</li>
 * </ul>
 * Without them the recorder falls back to the on-device DSP separation.
 */
public final class DiarizationModels {

    public static final String SEGMENTATION_ASSET = "diarization/segmentation.onnx";
    public static final String EMBEDDING_FILE = "wespeaker_en_voxceleb_resnet34_LM.onnx";
    public static final String EMBEDDING_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" + EMBEDDING_FILE;
    /** Rough size of the embedding model, for the download prompt. */
    public static final int EMBEDDING_SIZE_MB = 26;
    private static final long EMBEDDING_MIN_BYTES = 20L * 1024 * 1024;
    private static final String TAG = "DiarModels";

    public enum Status { ABSENT, DOWNLOADING, READY, ERROR }

    public interface Listener {
        /** Always on the main thread. {@code progress} is 0..100 while downloading, -1 otherwise. */
        void onStatus(Status status, int progress, @Nullable String message);
    }

    private static DiarizationModels instance;

    private final Context app;
    private final File root;
    private final File embedding;
    private final File segmentation;
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Status status;
    private volatile int progress = -1;
    private volatile String message;
    private volatile boolean cancelRequested;

    public static synchronized DiarizationModels get(Context context) {
        if (instance == null) {
            instance = new DiarizationModels(context.getApplicationContext());
        }
        return instance;
    }

    private DiarizationModels(Context context) {
        app = context;
        root = new File(context.getFilesDir(), "diarization");
        embedding = new File(root, EMBEDDING_FILE);
        segmentation = new File(root, "segmentation.onnx");
        status = isInstalled() ? Status.READY : Status.ABSENT;
    }

    public boolean isReady() {
        return status == Status.READY && isInstalled();
    }

    public Status status() {
        return status;
    }

    public int progress() {
        return progress;
    }

    @Nullable
    public String message() {
        return message;
    }

    @MainThread
    public void addListener(Listener l) {
        listeners.add(l);
        l.onStatus(status, progress, message);
    }

    @MainThread
    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private boolean isInstalled() {
        return embedding.isFile() && embedding.length() >= EMBEDDING_MIN_BYTES;
    }

    /** Absolute path of the segmentation model, copied out of the assets on first use. */
    public File segmentationFile() throws IOException {
        if (segmentation.isFile() && segmentation.length() > 0) {
            return segmentation;
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("não foi possível criar a pasta dos modelos");
        }
        File tmp = new File(root, "segmentation.onnx.part");
        try (InputStream in = app.getAssets().open(SEGMENTATION_ASSET); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        if (!tmp.renameTo(segmentation)) {
            throw new IOException("não foi possível instalar o modelo de segmentação");
        }
        return segmentation;
    }

    public File embeddingFile() {
        return embedding;
    }

    public void cancelDownload() {
        cancelRequested = true;
    }

    @MainThread
    public void download() {
        if (status == Status.DOWNLOADING) {
            return;
        }
        cancelRequested = false;
        publish(Status.DOWNLOADING, 0, null);
        AppExecutors.io(() -> {
            File part = new File(root, EMBEDDING_FILE + ".part");
            try {
                if (!root.isDirectory() && !root.mkdirs()) {
                    throw new IOException("não foi possível criar a pasta dos modelos");
                }
                segmentationFile();
                fetch(part);
                if (cancelRequested) {
                    throw new IOException("cancelado");
                }
                if (part.length() < EMBEDDING_MIN_BYTES) {
                    throw new IOException("arquivo do modelo incompleto");
                }
                //noinspection ResultOfMethodCallIgnored
                embedding.delete();
                if (!part.renameTo(embedding)) {
                    throw new IOException("não foi possível mover o modelo");
                }
                publish(Status.READY, -1, null);
            } catch (Exception e) {
                Logger.w(TAG, "download do modelo de vozes falhou: " + e.getMessage());
                publish(Status.ABSENT, -1, cancelRequested ? null : friendly(e));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        });
    }

    private void fetch(File target) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(EMBEDDING_URL).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            long total = body.contentLength();
            long done = 0;
            int lastPct = -1;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelRequested) {
                        throw new IOException("cancelado");
                    }
                    out.write(buffer, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            publish(Status.DOWNLOADING, pct, null);
                        }
                    }
                }
            }
        }
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.UnknownHostException || m.contains("Unable to resolve host")) {
            return "Sem conexão para baixar o modelo de vozes.";
        }
        if (m.startsWith("HTTP")) {
            return "O servidor do modelo respondeu " + m + ".";
        }
        return "Não foi possível instalar o modelo de vozes. Tente novamente.";
    }

    private void publish(Status s, int p, @Nullable String m) {
        status = s;
        progress = p;
        message = m;
        AppExecutors.main(() -> {
            for (Listener l : new ArrayList<>(listeners)) {
                l.onStatus(s, p, m);
            }
        });
    }
}
