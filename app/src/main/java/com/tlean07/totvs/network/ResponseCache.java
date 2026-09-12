package com.tlean07.totvs.network;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for read responses (dashboard, lists, alerts, users).
 *
 * The app used to hit the backend, and therefore the Oracle database, every time a screen
 * was opened. Now a screen first shows the cached response when it is younger than its TTL
 * and only goes to the network when the entry is missing, expired, or the user explicitly
 * pulls to refresh. Writes (new transcription, import, alert seen, user changes) invalidate
 * the affected entries, and logout clears everything because the data is per user.
 *
 * Process-scoped only: nothing is written to disk, so no sensitive data outlives the process.
 */
public final class ResponseCache {

    /** Default freshness window; the backend keeps its own server-side cache too. */
    public static final long DEFAULT_TTL_MS = 60 * 1000L;

    private static final ResponseCache INSTANCE = new ResponseCache();

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private ResponseCache() {
    }

    public static ResponseCache get() {
        return INSTANCE;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T get(String key, long ttlMs) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.storedAt > ttlMs) {
            entries.remove(key);
            return null;
        }
        return (T) entry.value;
    }

    public void put(String key, @Nullable Object value) {
        if (value == null) {
            entries.remove(key);
            return;
        }
        entries.put(key, new Entry(value, System.currentTimeMillis()));
    }

    /** Removes every entry whose key starts with {@code prefix}. */
    public void invalidate(String prefix) {
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clear() {
        entries.clear();
    }

    private static final class Entry {
        final Object value;
        final long storedAt;

        Entry(Object value, long storedAt) {
            this.value = value;
            this.storedAt = storedAt;
        }
    }
}
