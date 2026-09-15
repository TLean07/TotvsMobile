package com.tlean07.totvs.audio;

import android.content.Context;
import android.content.SharedPreferences;

/** User preferences of the meeting recorder (quality, retention, consent). */
public final class RecorderSettings {

    public static final int[] BITRATES = {32_000, 64_000, 128_000};
    public static final String[] BITRATE_LABELS = {"Econômica (32 kbps)", "Padrão (64 kbps)", "Alta (128 kbps)"};
    public static final int[] RETENTION_DAYS = {0, 7, 30};
    public static final String[] RETENTION_LABELS = {"Nunca", "Após 7 dias", "Após 30 dias"};

    private static final String PREFS = "gravador";
    private final SharedPreferences prefs;

    public RecorderSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int bitrate() {
        return prefs.getInt("bitrate", 64_000);
    }

    public void setBitrate(int bps) {
        prefs.edit().putInt("bitrate", bps).apply();
    }

    public int bitrateIndex() {
        for (int i = 0; i < BITRATES.length; i++) {
            if (BITRATES[i] == bitrate()) {
                return i;
            }
        }
        return 1;
    }

    /** Days after which recordings already sent for analysis are deleted (0 = keep). */
    public int retentionDays() {
        return prefs.getInt("retencao_dias", 0);
    }

    public void setRetentionDays(int days) {
        prefs.edit().putInt("retencao_dias", days).apply();
    }

    public int retentionIndex() {
        for (int i = 0; i < RETENTION_DAYS.length; i++) {
            if (RETENTION_DAYS[i] == retentionDays()) {
                return i;
            }
        }
        return 0;
    }

    public boolean consentAccepted() {
        return prefs.getBoolean("consentimento", false);
    }

    public void setConsentAccepted(boolean accepted) {
        prefs.edit().putBoolean("consentimento", accepted).apply();
    }

    /** Encrypt finished recordings at rest (on by default). */
    public boolean encryptAtRest() {
        return prefs.getBoolean("criptografar", true);
    }

    public void setEncryptAtRest(boolean on) {
        prefs.edit().putBoolean("criptografar", on).apply();
    }

    public boolean biometricForRecordings() {
        return prefs.getBoolean("biometria_gravacoes", false);
    }

    public void setBiometricForRecordings(boolean on) {
        prefs.edit().putBoolean("biometria_gravacoes", on).apply();
    }
}
