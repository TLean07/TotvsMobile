package com.tlean07.totvs.ui.alertas;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * "Treated" notes for alerts. The backend only knows "visto", so what the
 * seller did about the alert is kept on the device, keyed by alert id.
 */
public final class AlertNotes {

    private static final String PREFS = "alert_notes";
    private final SharedPreferences prefs;

    public AlertNotes(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    public String get(@Nullable String alertId) {
        return alertId == null ? null : prefs.getString(alertId, null);
    }

    public void put(String alertId, String note) {
        prefs.edit().putString(alertId, note).putLong(alertId + ":em", System.currentTimeMillis()).apply();
    }

    public long when(String alertId) {
        return prefs.getLong(alertId + ":em", 0);
    }
}
