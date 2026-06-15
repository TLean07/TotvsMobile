package com.tlean07.totvs.storage;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tlean07.totvs.network.PersistentCookieJar;
import com.tlean07.totvs.network.dto.AuthResponse;
import com.tlean07.totvs.network.dto.AuthUser;

/**
 * Owns the authenticated session.
 *
 * <ul>
 *   <li>The <b>access token</b> lives only in memory for the lifetime of the
 *       process (never persisted to disk), per the security requirements.</li>
 *   <li>The <b>refresh token</b> is held by {@link PersistentCookieJar} inside
 *       the encrypted store (it is an httpOnly cookie issued by the backend).</li>
 *   <li>Lightweight, non-secret session flags (biometric enabled, last active,
 *       cached display name) live in {@link SecureStorage}.</li>
 * </ul>
 */
public class SessionManager {

    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";
    private static final String KEY_SECURE_SCREEN = "secure_screen_enabled";
    private static final String KEY_USER_JSON = "session_user";
    private static final String KEY_LAST_ACTIVE = "last_active_at";

    private final SecureStorage storage;
    private final PersistentCookieJar cookieJar;
    private final Gson gson = new Gson();

    @Nullable
    private volatile String accessToken;
    @Nullable
    private volatile AuthUser currentUser;

    public SessionManager(SecureStorage storage, PersistentCookieJar cookieJar) {
        this.storage = storage;
        this.cookieJar = cookieJar;
        String cached = storage.getString(KEY_USER_JSON, null);
        if (cached != null) {
            this.currentUser = gson.fromJson(cached, AuthUser.class);
        }
    }

    /** Stores the result of a successful login/refresh. */
    public synchronized void setSession(AuthResponse response) {
        this.accessToken = response.accessToken;
        if (response.user != null) {
            this.currentUser = response.user;
            storage.putString(KEY_USER_JSON, gson.toJson(response.user));
        }
        touch();
    }

    @Nullable
    public String getAccessToken() {
        return accessToken;
    }

    @Nullable
    public AuthUser getCurrentUser() {
        return currentUser;
    }

    /** True when a refresh-token cookie is stored, i.e. a silent re-login is possible. */
    public boolean hasPersistentSession() {
        return cookieJar.hasRefreshCookie();
    }

    public boolean isLoggedInMemory() {
        return accessToken != null;
    }

    public boolean isBiometricEnabled() {
        return storage.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public void setBiometricEnabled(boolean enabled) {
        storage.putBoolean(KEY_BIOMETRIC_ENABLED, enabled);
    }

    public boolean isSecureScreenEnabled() {
        return storage.getBoolean(KEY_SECURE_SCREEN, true);
    }

    public void setSecureScreenEnabled(boolean enabled) {
        storage.putBoolean(KEY_SECURE_SCREEN, enabled);
    }

    public void touch() {
        storage.putLong(KEY_LAST_ACTIVE, System.currentTimeMillis());
    }

    public long lastActiveAt() {
        return storage.getLong(KEY_LAST_ACTIVE, 0L);
    }

    /** Drops the in-memory token but keeps the refresh cookie (used by the lock screen). */
    public synchronized void lock() {
        this.accessToken = null;
    }

    /** Full logout: clears tokens, cookies and session flags. */
    public synchronized void clear() {
        this.accessToken = null;
        this.currentUser = null;
        cookieJar.clear();
        storage.remove(KEY_USER_JSON, KEY_BIOMETRIC_ENABLED, KEY_LAST_ACTIVE);
    }
}
