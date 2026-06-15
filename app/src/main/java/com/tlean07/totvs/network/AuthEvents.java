package com.tlean07.totvs.network;

import android.os.Handler;
import android.os.Looper;

/**
 * Tiny event bus for "the backend revoked our session" — raised by
 * {@link TokenAuthenticator} when a refresh fails (expired/revoked refresh token
 * or a revoked device). The active screen listens and routes back to login.
 */
public final class AuthEvents {

    public interface Listener {
        void onSessionExpired();
    }

    private static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AuthEvents() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void notifySessionExpired() {
        Listener l = listener;
        if (l != null) {
            MAIN.post(l::onSessionExpired);
        }
    }
}
