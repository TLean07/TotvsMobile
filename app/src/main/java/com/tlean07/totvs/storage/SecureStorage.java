package com.tlean07.totvs.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.tlean07.totvs.util.Logger;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Secure key/value storage backed by the Android Keystore via
 * {@link EncryptedSharedPreferences}. Values are encrypted at rest (AES256-GCM)
 * with a master key that never leaves the Keystore.
 *
 * Used to persist the refresh-token cookie, the device-binding identity and the
 * biometric/security flags. Plain {@link SharedPreferences} are never used for
 * sensitive data.
 */
public final class SecureStorage {

    private static final String TAG = "SecureStorage";
    private static final String FILE = "totvs_secure_prefs";

    private final SharedPreferences prefs;

    public SecureStorage(Context context) {
        this.prefs = create(context.getApplicationContext());
    }

    private static SharedPreferences create(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Logger.e(TAG, "Failed to open encrypted prefs, recreating", e);
            // If the keystore entry is corrupt (e.g. after a restore), wipe and retry once.
            context.deleteSharedPreferences(FILE);
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                return EncryptedSharedPreferences.create(
                        context, FILE, masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            } catch (GeneralSecurityException | IOException ex) {
                throw new IllegalStateException("Unable to initialise secure storage", ex);
            }
        }
    }

    public String getString(String key, String def) {
        return prefs.getString(key, def);
    }

    public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    public boolean getBoolean(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public long getLong(String key, long def) {
        return prefs.getLong(key, def);
    }

    public void putLong(String key, long value) {
        prefs.edit().putLong(key, value).apply();
    }

    public void remove(String... keys) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : keys) {
            editor.remove(key);
        }
        editor.apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }
}
