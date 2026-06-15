package com.tlean07.totvs.cas;

/**
 * Local CAS (Controle de Acesso Seguro) device record. Field names mirror the
 * planned {@code mobile_devices} backend collection so the same object can be
 * sent to {@code POST /api/mobile/devices/register} once that endpoint exists.
 */
public class DeviceInfo {
    public String devicePublicId;   // non-secret, stable per install
    public String deviceName;
    public String deviceModel;
    public String osVersion;
    public String appVersion;
    public String platform = "android";
    public String boundUserId;      // user this install is currently bound to
    public String status = "active"; // active | revoked
    public long createdAt;
    public long lastSeenAt;
}
