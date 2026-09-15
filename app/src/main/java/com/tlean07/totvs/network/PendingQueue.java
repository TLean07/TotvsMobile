package com.tlean07.totvs.network;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.audio.store.Recording;
import com.tlean07.totvs.audio.store.RecordingStore;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.security.AuditLog;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import retrofit2.Response;

/**
 * Transcriptions the user tried to send without a connection. Each one is a
 * JSON file under {@code filesDir/fila}; {@link #flush} replays them when the
 * network is back (ConnectivityManager callback in {@link TotvsApp}) and when
 * the app comes to the foreground.
 */
public final class PendingQueue {

    public interface FlushListener {
        void onFlushed(int sent, int remaining);
    }

    /** One queued request; {@code recordingId} lets the local recording flip to "enviada". */
    public static final class Item {
        public String id;
        public long em;
        public TranscricaoRequest request;
        @Nullable
        public String recordingId;
    }

    private static final String TAG = "PendingQueue";
    private static final Gson GSON = new Gson();
    private static PendingQueue instance;

    private final Context app;
    private final File dir;
    private final AtomicBoolean flushing = new AtomicBoolean();

    public static synchronized PendingQueue get(Context context) {
        if (instance == null) {
            instance = new PendingQueue(context.getApplicationContext());
        }
        return instance;
    }

    private PendingQueue(Context app) {
        this.app = app;
        this.dir = new File(app.getFilesDir(), "fila");
    }

    public synchronized void enqueue(TranscricaoRequest request, @Nullable String recordingId) {
        Item item = new Item();
        item.id = UUID.randomUUID().toString();
        item.em = System.currentTimeMillis();
        item.request = request;
        item.recordingId = recordingId;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("fila indisponível");
            }
            Files.write(new File(dir, item.id + ".json").toPath(), GSON.toJson(item).getBytes(StandardCharsets.UTF_8));
            AuditLog.get(app).record("fila.enfileirada", item.id + (recordingId != null ? " · gravação " + recordingId : ""));
        } catch (IOException e) {
            Logger.w(TAG, "não foi possível enfileirar: " + e.getMessage());
        }
    }

    public synchronized int size() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        return files == null ? 0 : files.length;
    }

    public synchronized List<Item> items() {
        List<Item> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            try {
                out.add(GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Item.class));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        out.sort((a, b) -> Long.compare(a.em, b.em));
        return out;
    }

    /** Sends everything queued, oldest first; stops at the first network failure. */
    public void flush(@Nullable FlushListener listener) {
        if (size() == 0 || !TotvsApp.get().session().hasPersistentSession() || !flushing.compareAndSet(false, true)) {
            return;
        }
        AppExecutors.io(() -> {
            int sent = 0;
            try {
                ApiService api = TotvsApp.get().dataRepository().api();
                RecordingStore store = new RecordingStore(app);
                for (Item item : items()) {
                    try {
                        Response<TranscricaoResponse> response = api.criarTranscricao(item.request).execute();
                        if (response.isSuccessful()) {
                            sent++;
                            //noinspection ResultOfMethodCallIgnored
                            new File(dir, item.id + ".json").delete();
                            if (item.recordingId != null) {
                                Recording r = store.load(item.recordingId);
                                if (r != null) {
                                    r.status = Recording.STATUS_ENVIADA;
                                    r.transcricaoId = response.body() == null ? null : response.body().id;
                                    store.save(r);
                                }
                            }
                            AuditLog.get(app).record("fila.enviada", item.id);
                        } else if (response.code() >= 400 && response.code() < 500 && response.code() != 401 && response.code() != 429) {
                            // The server rejected it for good: drop instead of retrying forever.
                            //noinspection ResultOfMethodCallIgnored
                            new File(dir, item.id + ".json").delete();
                            AuditLog.get(app).record("fila.rejeitada", item.id + " · HTTP " + response.code());
                        } else {
                            break;
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
                if (sent > 0) {
                    TotvsApp.get().dataRepository().invalidateTranscricoes();
                }
            } finally {
                flushing.set(false);
                int remaining = size();
                int done = sent;
                if (listener != null) {
                    AppExecutors.main(() -> listener.onFlushed(done, remaining));
                }
            }
        });
    }
}
