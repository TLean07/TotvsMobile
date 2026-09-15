package com.tlean07.totvs.audio.store;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tlean07.totvs.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Recordings live in {@code filesDir/gravacoes/<id>/} as {@code meta.json} plus
 * the audio (WAV while recording, M4A once finished). Nothing leaves the
 * device until the user sends the transcript or the audio for analysis.
 */
public final class RecordingStore {

    public static final String WAV = "audio.wav";
    public static final String M4A = "audio.m4a";
    private static final String META = "meta.json";
    private static final String TAG = "RecordingStore";

    private final File root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public RecordingStore(Context context) {
        root = new File(context.getFilesDir(), "gravacoes");
    }

    public File dir(String id) {
        return new File(root, id);
    }

    public File newDir() throws IOException {
        String id = UUID.randomUUID().toString();
        File d = dir(id);
        if (!d.isDirectory() && !d.mkdirs()) {
            throw new IOException("não foi possível criar a pasta da gravação");
        }
        return d;
    }

    public File audioFile(Recording r) {
        File d = dir(r.id);
        if (r.audioArquivo != null) {
            File f = new File(d, r.audioArquivo);
            if (f.exists()) {
                return f;
            }
        }
        File m4a = new File(d, M4A);
        return m4a.exists() ? m4a : new File(d, WAV);
    }

    public synchronized void save(Recording r) throws IOException {
        File d = dir(r.id);
        if (!d.isDirectory() && !d.mkdirs()) {
            throw new IOException("pasta da gravação indisponível");
        }
        File tmp = new File(d, META + ".tmp");
        Files.write(tmp.toPath(), gson.toJson(r).getBytes(StandardCharsets.UTF_8));
        File meta = new File(d, META);
        if (!tmp.renameTo(meta)) {
            Files.move(tmp.toPath(), meta.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Nullable
    public synchronized Recording load(String id) {
        File meta = new File(dir(id), META);
        if (!meta.exists()) {
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8);
            Recording r = gson.fromJson(json, Recording.class);
            if (r != null && r.id == null) {
                r.id = id;
            }
            return r;
        } catch (Exception e) {
            Logger.w(TAG, "meta ilegível em " + id);
            return null;
        }
    }

    /** Newest first. */
    public synchronized List<Recording> list() {
        List<Recording> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) {
            return out;
        }
        for (File d : dirs) {
            Recording r = load(d.getName());
            if (r != null) {
                out.add(r);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.criadoEm, a.criadoEm));
        return out;
    }

    public synchronized int count() {
        File[] dirs = root.listFiles(File::isDirectory);
        int n = 0;
        if (dirs != null) {
            for (File d : dirs) {
                if (new File(d, META).exists()) {
                    n++;
                }
            }
        }
        return n;
    }

    public synchronized void delete(String id) {
        deleteRecursively(dir(id));
    }

    /** Directories without meta.json (crash while recording) – the caller may recover or drop them. */
    public synchronized List<File> orphans() {
        List<File> out = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                if (!new File(d, META).exists()) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    public static void deleteRecursively(File f) {
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
}
