package com.tlean07.totvs.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-wide background executor + a helper to bounce results to the main thread. */
public final class AppExecutors {

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppExecutors() {
    }

    public static void io(Runnable r) {
        IO.execute(r);
    }

    public static void main(Runnable r) {
        MAIN.post(r);
    }
}
