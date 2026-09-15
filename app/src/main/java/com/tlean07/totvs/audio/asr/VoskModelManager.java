package com.tlean07.totvs.audio.asr;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Logger;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keeps the Portuguese Vosk model on the device. The model (~32 MB zipped) is
 * downloaded once from the Vosk project site into the app's private files and
 * unpacked; after that recognition is fully offline. The APK stays small and
 * the recorder still works without the model – it just records audio and
 * detects speakers, and the review screen offers the server-side transcription.
 */
public final class VoskModelManager {

    public static final String MODEL_NAME = "vosk-model-small-pt-0.3";
    public static final String MODEL_LABEL = "Vosk small pt-BR 0.3";
    public static final int MODEL_SIZE_MB = 32;
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    private static final String TAG = "VoskModel";

    public enum Status { ABSENT, DOWNLOADING, UNPACKING, READY, ERROR }

    public interface Listener {
        /** Always on the main thread. {@code progress} is 0..100 while downloading, -1 otherwise. */
        void onStatus(Status status, int progress, @Nullable String message);
    }

    private static VoskModelManager instance;

    private final File root;
    private final File modelDir;
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Status status;
    private volatile int progress = -1;
    private volatile String message;
    private volatile boolean cancelRequested;

    public static synchronized VoskModelManager get(Context context) {
        if (instance == null) {
            instance = new VoskModelManager(context.getApplicationContext());
        }
        return instance;
    }

    private VoskModelManager(Context context) {
        root = new File(context.getFilesDir(), "vosk");
        modelDir = new File(root, MODEL_NAME);
        status = isInstalled() ? Status.READY : Status.ABSENT;
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
        } catch (Throwable t) {
            Logger.w(TAG, "libvosk indisponível: " + t.getClass().getSimpleName());
        }
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

    public File modelDir() {
        return modelDir;
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
        return new File(modelDir, "final.mdl").exists()
                || new File(new File(modelDir, "am"), "final.mdl").exists();
    }

    /** Loads the model (heavy, ~1-2 s); call from a background thread. */
    public Model load() throws IOException {
        if (!isInstalled()) {
            throw new IOException("modelo não instalado");
        }
        return new Model(modelDir.getAbsolutePath());
    }

    public void cancelDownload() {
        cancelRequested = true;
    }

    /** Removes the model files (settings screen / recovery). */
    public void delete() {
        deleteRecursively(modelDir);
        publish(Status.ABSENT, -1, null);
    }

    @MainThread
    public void download() {
        if (status == Status.DOWNLOADING || status == Status.UNPACKING) {
            return;
        }
        cancelRequested = false;
        publish(Status.DOWNLOADING, 0, null);
        AppExecutors.io(() -> {
            File zip = new File(root, MODEL_NAME + ".zip.part");
            File staging = new File(root, MODEL_NAME + ".staging");
            try {
                if (!root.isDirectory() && !root.mkdirs()) {
                    throw new IOException("não foi possível criar a pasta do modelo");
                }
                fetch(zip);
                if (cancelRequested) {
                    throw new IOException("cancelado");
                }
                publish(Status.UNPACKING, -1, null);
                deleteRecursively(staging);
                unzip(zip, staging);
                File unpacked = new File(staging, MODEL_NAME);
                if (!unpacked.isDirectory()) {
                    File[] children = staging.listFiles();
                    unpacked = children != null && children.length == 1 && children[0].isDirectory() ? children[0] : staging;
                }
                deleteRecursively(modelDir);
                if (!unpacked.renameTo(modelDir)) {
                    throw new IOException("não foi possível mover o modelo");
                }
                if (!isInstalled()) {
                    throw new IOException("arquivo do modelo incompleto");
                }
                publish(Status.READY, -1, null);
            } catch (Exception e) {
                Logger.w(TAG, "download do modelo falhou: " + e.getMessage());
                deleteRecursively(modelDir);
                publish(Status.ABSENT, -1, cancelRequested ? null : friendly(e));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
                deleteRecursively(staging);
            }
        });
    }

    private void fetch(File target) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(MODEL_URL).build();
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

    private static void unzip(File zip, File into) throws IOException {
        String base = into.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                File out = new File(into, entry.getName());
                if (!out.getCanonicalPath().startsWith(base)) {
                    throw new IOException("entrada inválida no zip");
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("não foi possível criar " + parent);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buffer)) != -1) {
                        os.write(buffer, 0, n);
                    }
                }
                zin.closeEntry();
            }
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof java.net.UnknownHostException || m.contains("Unable to resolve host")) {
            return "Sem conexão para baixar o modelo de voz.";
        }
        if (m.startsWith("HTTP")) {
            return "O servidor do modelo respondeu " + m + ".";
        }
        return "Não foi possível instalar o modelo de voz. Tente novamente.";
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
