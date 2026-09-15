package com.tlean07.totvs.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.tlean07.totvs.R;
import com.tlean07.totvs.audio.asr.TranscriptFormatter;
import com.tlean07.totvs.ui.gravador.GravadorActivity;

/**
 * Foreground service that keeps the {@link RecorderSession} alive while the
 * screen is off or the user switches apps, with a persistent notification
 * offering pause/resume/stop.
 */
public class MeetingRecorderService extends Service {

    public static final String ACTION_START = "com.tlean07.totvs.gravador.START";
    public static final String ACTION_PAUSE = "com.tlean07.totvs.gravador.PAUSE";
    public static final String ACTION_RESUME = "com.tlean07.totvs.gravador.RESUME";
    public static final String ACTION_STOP = "com.tlean07.totvs.gravador.STOP";

    private static final String CHANNEL = "gravacao_reuniao";
    private static final int NOTIFICATION_ID = 4101;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RecorderSession session;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshNotification();
            handler.postDelayed(this, 1_000);
        }
    };

    private final RecorderSession.Listener listener = new RecorderSession.Listener() {
        @Override
        public void onState(RecorderSession.State state, @Nullable String message) {
            refreshNotification();
            if (state == RecorderSession.State.DONE || state == RecorderSession.State.ERROR) {
                handler.removeCallbacks(tick);
                ServiceCompat.stopForeground(MeetingRecorderService.this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }

        @Override
        public void onSnapshot(AudioPipeline.Snapshot snapshot) {
        }

        @Override
        public void onLevel(float dbfs, long positionMs) {
        }

        @Override
        public void onFinishProgress(int percent) {
        }
    };

    public static void start(Context context) {
        Intent i = new Intent(context, MeetingRecorderService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_START.equals(action) || session == null) {
            ensureChannel();
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            if (session == null) {
                session = RecorderSession.start(this);
                session.addListener(listener);
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
        }
        if (session != null && action != null) {
            switch (action) {
                case ACTION_PAUSE:
                    session.pause();
                    break;
                case ACTION_RESUME:
                    session.resume();
                    break;
                case ACTION_STOP:
                    session.stop();
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // App swiped away: finish the recording so nothing is lost.
        if (session != null && session.isActive()) {
            session.stop();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (session != null) {
            session.removeListener(listener);
            session = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        RecorderSession s = session != null ? session : RecorderSession.current();
        RecorderSession.State state = s == null ? RecorderSession.State.PREPARING : s.state();
        String clock = s == null ? "00:00" : TranscriptFormatter.formatClock(s.positionMs());
        String title;
        switch (state) {
            case RECORDING:
                title = getString(R.string.grav_notif_recording, clock);
                break;
            case PAUSED:
                title = getString(R.string.grav_notif_paused, clock);
                break;
            case FINISHING:
                title = getString(R.string.grav_notif_finishing);
                break;
            default:
                title = getString(R.string.grav_notif_preparing);
                break;
        }
        Intent open = new Intent(this, GravadorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(title)
                .setContentText(getString(R.string.grav_notif_text))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setColor(ContextCompat.getColor(this, R.color.totvs_primary));
        if (state == RecorderSession.State.RECORDING) {
            b.addAction(R.drawable.ic_pause, getString(R.string.grav_pause), action(ACTION_PAUSE, 1));
            b.addAction(R.drawable.ic_stop, getString(R.string.grav_stop), action(ACTION_STOP, 3));
        } else if (state == RecorderSession.State.PAUSED) {
            b.addAction(R.drawable.ic_mic, getString(R.string.grav_resume), action(ACTION_RESUME, 2));
            b.addAction(R.drawable.ic_stop, getString(R.string.grav_stop), action(ACTION_STOP, 3));
        }
        return b.build();
    }

    private PendingIntent action(String action, int code) {
        Intent i = new Intent(this, MeetingRecorderService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void ensureChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.grav_notif_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.grav_notif_channel_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
