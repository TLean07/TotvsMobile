package com.tlean07.totvs.util;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes the full stack trace of any uncaught exception to a plain-text file the
 * user can retrieve without adb:
 * {@code Android/data/com.tlean07.totvs/files/last_crash.txt}.
 *
 * It still chains to the platform's default handler, so the OS crash dialog
 * keeps showing — this only captures the cause for diagnosis.
 */
public final class CrashReporter {

    private CrashReporter() {
    }

    public static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrash(app, thread, throwable);
            } catch (Throwable ignored) {
                // Never fail inside the crash handler.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    private static void writeCrash(Context app, Thread thread, Throwable throwable) {
        File dir = app.getExternalFilesDir(null);
        if (dir == null) {
            dir = app.getFilesDir();
        }
        File file = new File(dir, "last_crash.txt");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        pw.println("TOTVS Mobile crash");
        pw.println("Time: " + when);
        pw.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + ")");
        pw.println("Thread: " + thread.getName());
        pw.println("----");
        throwable.printStackTrace(pw);
        pw.flush();

        try (PrintWriter out = new PrintWriter(file, "UTF-8")) {
            out.print(sw.toString());
        } catch (Exception ignored) {
            // best effort
        }
    }
}
