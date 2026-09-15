package com.tlean07.totvs.work;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.tlean07.totvs.R;
import com.tlean07.totvs.TotvsApp;
import com.tlean07.totvs.network.PendingQueue;
import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.ui.SplashActivity;
import com.tlean07.totvs.util.Logger;
import com.tlean07.totvs.widget.ResumoWidget;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import retrofit2.Response;

/**
 * Every 15 minutes (the WorkManager minimum) asks the backend for new alerts
 * and notifies the ones not seen before – high/critical ones individually,
 * the rest grouped. Also refreshes the home-screen widget and replays the
 * offline queue while it is at it.
 */
public class AlertasWorker extends Worker {

    public static final String CHANNEL = "alertas";
    private static final String WORK = "alertas-novos";
    private static final String PREFS = "alertas_notificados";
    private static final String TAG = "AlertasWorker";

    public AlertasWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Idempotent; call after login / on app start. */
    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(AlertasWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK);
    }

    public static void ensureChannel(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.notif_channel_alertas), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notif_channel_alertas_desc));
        nm.createNotificationChannel(channel);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        TotvsApp app = TotvsApp.get();
        if (app == null || !app.session().hasPersistentSession()) {
            return Result.success();
        }
        try {
            PendingQueue.get(ctx).flush(null);
            Response<List<AlertaResponse>> response = app.dataRepository().api().alertasNovos().execute();
            if (!response.isSuccessful() || response.body() == null) {
                return response.code() == 401 ? Result.success() : Result.retry();
            }
            List<AlertaResponse> novos = response.body();
            ResumoWidget.publishAlertas(ctx, novos.size());
            notifyNew(ctx, novos);
            return Result.success();
        } catch (Exception e) {
            Logger.w(TAG, "falha ao buscar alertas: " + e.getClass().getSimpleName());
            return Result.retry();
        }
    }

    private void notifyNew(Context ctx, List<AlertaResponse> novos) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> seen = new HashSet<>(prefs.getStringSet("ids", new HashSet<>()));
        boolean first = !prefs.getBoolean("inicializado", false);
        int fresh = 0;
        AlertaResponse highlight = null;
        Set<String> now = new HashSet<>();
        for (AlertaResponse a : novos) {
            if (a.id == null) {
                continue;
            }
            now.add(a.id);
            if (!seen.contains(a.id)) {
                fresh++;
                if (highlight == null || isHigh(a.prioridade)) {
                    highlight = a;
                }
            }
        }
        prefs.edit().putStringSet("ids", now).putBoolean("inicializado", true).apply();
        if (first || fresh == 0) {
            // First run only learns what already exists; no retroactive noise.
            return;
        }
        ensureChannel(ctx);
        Intent open = new Intent(ctx, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = fresh == 1 && highlight != null
                ? ctx.getString(R.string.notif_alert_title, highlight.titulo != null ? highlight.titulo : "Alerta")
                : ctx.getString(R.string.notif_alerts_many, fresh);
        String text = highlight != null && highlight.mensagem != null ? highlight.mensagem : ctx.getString(R.string.notif_channel_alertas_desc);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notifications)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(highlight != null && isHigh(highlight.prioridade) ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT)
                .setColor(ctx.getColor(R.color.totvs_primary));
        try {
            NotificationManagerCompat.from(ctx).notify(4200, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted
        }
    }

    private static boolean isHigh(String p) {
        if (p == null) {
            return false;
        }
        String u = p.toUpperCase();
        return u.startsWith("ALT") || u.startsWith("CRIT") || u.equals("HIGH");
    }
}
