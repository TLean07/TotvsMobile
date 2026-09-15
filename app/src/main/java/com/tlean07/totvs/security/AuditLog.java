package com.tlean07.totvs.security;

import android.content.Context;

import com.google.gson.Gson;
import com.tlean07.totvs.util.AppExecutors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local, append-only trail of what the app did with sensitive data (recordings,
 * uploads, feedback, logins). Shown on the Segurança tab; never uploaded.
 */
public final class AuditLog {

    public static final class Entry {
        public long em;
        public String evento;
        public String detalhe;
    }

    private static final int MAX_ENTRIES = 500;
    private static final Gson GSON = new Gson();
    private static AuditLog instance;

    private final File file;

    public static synchronized AuditLog get(Context context) {
        if (instance == null) {
            instance = new AuditLog(context.getApplicationContext());
        }
        return instance;
    }

    private AuditLog(Context app) {
        file = new File(app.getFilesDir(), "auditoria.jsonl");
    }

    public void record(String evento, String detalhe) {
        Entry e = new Entry();
        e.em = System.currentTimeMillis();
        e.evento = evento;
        e.detalhe = detalhe;
        AppExecutors.io(() -> append(e));
    }

    private synchronized void append(Entry e) {
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(GSON.toJson(e));
            w.write('\n');
        } catch (IOException ignored) {
            // audit is best effort
        }
        trimIfNeeded();
    }

    private void trimIfNeeded() {
        if (file.length() < 200 * 1024) {
            return;
        }
        List<Entry> all = readAll();
        if (all.size() <= MAX_ENTRIES) {
            return;
        }
        List<Entry> keep = all.subList(all.size() - MAX_ENTRIES, all.size());
        try (FileWriter w = new FileWriter(file, false)) {
            for (Entry e : keep) {
                w.write(GSON.toJson(e));
                w.write('\n');
            }
        } catch (IOException ignored) {
            // idem
        }
    }

    /** Oldest first. */
    public synchronized List<Entry> readAll() {
        List<Entry> out = new ArrayList<>();
        if (!file.exists()) {
            return out;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    try {
                        out.add(GSON.fromJson(line, Entry.class));
                    } catch (RuntimeException ignored) {
                        // corrupt line
                    }
                }
            }
        } catch (IOException ignored) {
            // empty
        }
        return out;
    }

    /** Newest first, at most {@code limit}. */
    public List<Entry> latest(int limit) {
        List<Entry> all = readAll();
        Collections.reverse(all);
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    public synchronized void clear() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
