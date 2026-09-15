package com.tlean07.totvs.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import com.tlean07.totvs.R;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.ui.SplashActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Home-screen widget with the three numbers a manager checks first. It reads
 * the last dashboard the app (or the alert worker) saved, so it never needs
 * the network itself; tapping opens the app.
 */
public class ResumoWidget extends AppWidgetProvider {

    private static final String PREFS = "widget";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, build(context));
        }
    }

    /** Saves the latest numbers and refreshes every placed widget. */
    public static void publish(Context context, DashboardResponse d) {
        long riscos = d.riscoPorTipo == null ? d.totalRiscos : d.totalRiscos;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("riscos", riscos)
                .putLong("oportunidades", d.totalOportunidades)
                .putLong("alertas", d.alertasNovos)
                .putLong("em", System.currentTimeMillis())
                .apply();
        refresh(context);
    }

    public static void publishAlertas(Context context, long alertasNovos) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("alertas", alertasNovos)
                .putLong("em", System.currentTimeMillis())
                .apply();
        refresh(context);
    }

    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ResumoWidget.class));
        if (ids.length > 0) {
            manager.updateAppWidget(ids, build(context));
        }
    }

    private static RemoteViews build(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_resumo);
        views.setTextViewText(R.id.widgetRiscos, String.valueOf(p.getLong("riscos", 0)));
        views.setTextViewText(R.id.widgetOportunidades, String.valueOf(p.getLong("oportunidades", 0)));
        views.setTextViewText(R.id.widgetAlertas, String.valueOf(p.getLong("alertas", 0)));
        long em = p.getLong("em", 0);
        views.setTextViewText(R.id.widgetAtualizado, em == 0 ? context.getString(R.string.widget_login)
                : context.getString(R.string.widget_updated,
                new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(em))));
        Intent open = new Intent(context, SplashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
}
