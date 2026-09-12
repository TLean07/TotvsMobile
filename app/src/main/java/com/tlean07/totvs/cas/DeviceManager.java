package com.tlean07.totvs.cas;

import android.os.Build;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tlean07.totvs.network.dto.AuthUser;
import com.tlean07.totvs.storage.SecureStorage;
import com.tlean07.totvs.util.Logger;

import java.util.UUID;

/**
 * Mobile CAS / device-binding manager.
 *
 * Each install gets a stable, non-secret {@code devicePublicId} (sent on every
 * request as {@code X-Device-Id} for backend auditing) stored encrypted in the
 * Keystore. On login the install is bound to the authenticated user. The
 * server-side counterpart is the refresh-token session already persisted in
 * Oracle Database ({@code REFRESH_TOKENS}); revoking it there (logout, refresh reuse, or
 * a future {@code /api/mobile/devices/{id}/revoke} endpoint) immediately breaks
 * this device's ability to refresh — biometrics cannot override that.
 */
public class DeviceManager {

    private static final String TAG = "DeviceManager";
    private static final String KEY_DEVICE = "cas_device";

    private final SecureStorage storage;
    private final Gson gson = new Gson();
    private final String appVersion;
    private DeviceInfo device;

    public DeviceManager(SecureStorage storage, String appVersion) {
        this.storage = storage;
        this.appVersion = appVersion;
        this.device = loadOrCreate();
    }

    private DeviceInfo loadOrCreate() {
        String json = storage.getString(KEY_DEVICE, null);
        if (json != null) {
            DeviceInfo existing = gson.fromJson(json, DeviceInfo.class);
            if (existing != null && existing.devicePublicId != null) {
                existing.appVersion = appVersion;
                existing.lastSeenAt = System.currentTimeMillis();
                save(existing);
                return existing;
            }
        }
        DeviceInfo created = new DeviceInfo();
        created.devicePublicId = UUID.randomUUID().toString();
        created.deviceName = Build.MANUFACTURER + " " + Build.MODEL;
        created.deviceModel = Build.MODEL;
        created.osVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        created.appVersion = appVersion;
        created.createdAt = System.currentTimeMillis();
        created.lastSeenAt = created.createdAt;
        save(created);
        Logger.d(TAG, "Created device identity " + created.devicePublicId);
        return created;
    }

    public String getDeviceId() {
        return device.devicePublicId;
    }

    public DeviceInfo getDevice() {
        return device;
    }

    /** Binds this install to the authenticated user (re-binds if a new user logs in). */
    public synchronized void bindToUser(AuthUser user) {
        device.boundUserId = user.id;
        device.status = "active";
        device.lastSeenAt = System.currentTimeMillis();
        save(device);
    }

    public boolean isBoundTo(@Nullable String userId) {
        return userId != null && userId.equals(device.boundUserId);
    }

    @Nullable
    public String boundUserId() {
        return device.boundUserId;
    }

    /** Local revocation. The session is also revoked server-side via logout. */
    public synchronized void revokeLocal() {
        device.status = "revoked";
        device.boundUserId = null;
        save(device);
    }

    private void save(DeviceInfo info) {
        this.device = info;
        storage.putString(KEY_DEVICE, gson.toJson(info));
    }
}
