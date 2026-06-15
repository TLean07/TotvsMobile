package com.tlean07.totvs.network;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tlean07.totvs.storage.SecureStorage;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;

/**
 * A {@link CookieJar} that persists cookies (notably the backend's httpOnly
 * {@code refresh_token}) inside {@link SecureStorage}, i.e. encrypted by the
 * Android Keystore. This is how the mobile client reuses the exact same
 * refresh-token mechanism as the web app without ever storing the token in
 * plain SharedPreferences.
 */
public class PersistentCookieJar implements CookieJar {

    public static final String REFRESH_COOKIE = "refresh_token";
    private static final String KEY = "cookie_store";
    private static final Type LIST_TYPE = new TypeToken<List<StoredCookie>>() {}.getType();

    private final SecureStorage storage;
    private final Gson gson = new Gson();
    // name -> cookie (one value per name is enough for this backend)
    private final Map<String, StoredCookie> cookies = new LinkedHashMap<>();

    public PersistentCookieJar(SecureStorage storage) {
        this.storage = storage;
        load();
    }

    @Override
    public synchronized void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> responseCookies) {
        boolean changed = false;
        for (Cookie cookie : responseCookies) {
            if (cookie.value().isEmpty() || cookie.expiresAt() < System.currentTimeMillis()) {
                // Server cleared the cookie (e.g. logout) — drop it.
                if (cookies.remove(cookie.name()) != null) {
                    changed = true;
                }
            } else {
                cookies.put(cookie.name(), StoredCookie.from(cookie));
                changed = true;
            }
        }
        if (changed) {
            persist();
        }
    }

    @NonNull
    @Override
    public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl url) {
        List<Cookie> matches = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (StoredCookie sc : cookies.values()) {
            if (sc.expiresAt < now) {
                expired.add(sc.name);
                continue;
            }
            Cookie cookie = sc.toCookie();
            if (cookie.matches(url)) {
                matches.add(cookie);
            }
        }
        if (!expired.isEmpty()) {
            for (String name : expired) {
                cookies.remove(name);
            }
            persist();
        }
        return matches;
    }

    public synchronized boolean hasRefreshCookie() {
        StoredCookie sc = cookies.get(REFRESH_COOKIE);
        return sc != null && sc.expiresAt >= System.currentTimeMillis();
    }

    public synchronized void clear() {
        cookies.clear();
        storage.remove(KEY);
    }

    private void load() {
        String json = storage.getString(KEY, null);
        if (json == null) {
            return;
        }
        List<StoredCookie> list = gson.fromJson(json, LIST_TYPE);
        if (list != null) {
            for (StoredCookie sc : list) {
                cookies.put(sc.name, sc);
            }
        }
    }

    private void persist() {
        storage.putString(KEY, gson.toJson(new ArrayList<>(cookies.values())));
    }

    /** Serializable snapshot of an OkHttp cookie. */
    static class StoredCookie {
        String name;
        String value;
        String domain;
        String path;
        long expiresAt;
        boolean secure;
        boolean httpOnly;
        boolean hostOnly;

        static StoredCookie from(Cookie c) {
            StoredCookie s = new StoredCookie();
            s.name = c.name();
            s.value = c.value();
            s.domain = c.domain();
            s.path = c.path();
            s.expiresAt = c.expiresAt();
            s.secure = c.secure();
            s.httpOnly = c.httpOnly();
            s.hostOnly = c.hostOnly();
            return s;
        }

        Cookie toCookie() {
            Cookie.Builder b = new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .path(path)
                    .expiresAt(expiresAt);
            if (hostOnly) {
                b.hostOnlyDomain(domain);
            } else {
                b.domain(domain);
            }
            if (secure) {
                b.secure();
            }
            if (httpOnly) {
                b.httpOnly();
            }
            return b.build();
        }
    }
}
