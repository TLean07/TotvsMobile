package com.tlean07.totvs.util;

import android.util.Log;

import com.tlean07.totvs.BuildConfig;

/**
 * Thin logging wrapper. Logs are emitted only in debug builds and every message
 * is run through a sanitizer so access/refresh tokens, passwords and bearer
 * headers never reach logcat.
 */
public final class Logger {

    private Logger() {
    }

    public static void d(String tag, String message) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, sanitize(message));
        }
    }

    public static void w(String tag, String message) {
        if (BuildConfig.DEBUG) {
            Log.w(tag, sanitize(message));
        }
    }

    public static void e(String tag, String message, Throwable t) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, sanitize(message), t);
        }
    }

    /** Redacts anything that looks like a secret before it is written out. */
    public static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String redacted = message
                .replaceAll("(?i)(authorization\\s*[:=]\\s*)bearer\\s+[A-Za-z0-9._\\-]+", "$1Bearer ***")
                .replaceAll("(?i)(\"?accessToken\"?\\s*[:=]\\s*\"?)[A-Za-z0-9._\\-]+", "$1***")
                .replaceAll("(?i)(\"?password\"?\\s*[:=]\\s*\"?)[^\",}\\s]+", "$1***")
                .replaceAll("(?i)(refresh_token=)[^;\\s]+", "$1***");
        return redacted;
    }
}
