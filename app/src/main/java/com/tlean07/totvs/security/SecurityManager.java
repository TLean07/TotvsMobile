package com.tlean07.totvs.security;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.view.WindowManager;

import com.tlean07.totvs.storage.SessionManager;

/**
 * Centralises mobile hardening:
 * <ul>
 *   <li>FLAG_SECURE on sensitive screens (blocks screenshots / screen capture),</li>
 *   <li>inactivity lock that requires biometric re-auth after a timeout,</li>
 *   <li>debug-build detection (warn, don't block).</li>
 * </ul>
 */
public final class SecurityManager {

    /** Lock the app after this much time in the background (config 7.session). */
    public static final long LOCK_AFTER_MS = 120_000L;

    private SecurityManager() {
    }

    /** Applies or clears FLAG_SECURE for a sensitive screen, honouring the user setting. */
    public static void applySecureFlag(Activity activity, boolean sensitive, SessionManager session) {
        boolean secure = sensitive && session.isSecureScreenEnabled() && !captureAllowedForTesting(activity);
        if (secure) {
            activity.getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /**
     * True when the app should require biometric re-auth on resume: biometric
     * lock is enabled and the inactivity window has elapsed.
     */
    public static boolean shouldLock(SessionManager session) {
        if (!session.isBiometricEnabled()) {
            return false;
        }
        long last = session.lastActiveAt();
        if (last == 0L) {
            return true;
        }
        return System.currentTimeMillis() - last > LOCK_AFTER_MS;
    }

    /**
     * Debug builds only: {@code adb shell settings put global totvs_allow_capture 1}
     * lets emulator screenshots / UI tests see sensitive screens. Ignored in release.
     */
    private static boolean captureAllowedForTesting(Context context) {
        if (!com.tlean07.totvs.BuildConfig.DEBUG) {
            return false;
        }
        try {
            return android.provider.Settings.Global.getInt(context.getContentResolver(), "totvs_allow_capture", 0) == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
