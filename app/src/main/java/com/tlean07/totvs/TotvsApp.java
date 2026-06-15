package com.tlean07.totvs;

import android.app.Application;

import com.tlean07.totvs.auth.AuthRepository;
import com.tlean07.totvs.auth.passkey.PasskeyManager;
import com.tlean07.totvs.cas.DeviceManager;
import com.tlean07.totvs.network.ApiClient;
import com.tlean07.totvs.network.DataRepository;
import com.tlean07.totvs.network.PersistentCookieJar;
import com.tlean07.totvs.storage.SecureStorage;
import com.tlean07.totvs.storage.SessionManager;

/**
 * Composition root / service locator. A single place that wires the encrypted
 * storage, cookie jar, session, device-binding (CAS) and the network stack so
 * the rest of the app just asks {@code TotvsApp.get()} for what it needs.
 */
public class TotvsApp extends Application {

    private static TotvsApp instance;

    private SecureStorage secureStorage;
    private SessionManager sessionManager;
    private DeviceManager deviceManager;
    private ApiClient apiClient;
    private AuthRepository authRepository;
    private DataRepository dataRepository;
    private PasskeyManager passkeyManager;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        secureStorage = new SecureStorage(this);
        PersistentCookieJar cookieJar = new PersistentCookieJar(secureStorage);
        sessionManager = new SessionManager(secureStorage, cookieJar);
        deviceManager = new DeviceManager(secureStorage, BuildConfig.VERSION_NAME);

        apiClient = new ApiClient(
                BuildConfig.API_BASE_URL,
                cookieJar,
                sessionManager,
                deviceManager.getDeviceId());

        authRepository = new AuthRepository(apiClient.api(), sessionManager, deviceManager);
        dataRepository = new DataRepository(apiClient.api());
        passkeyManager = new PasskeyManager(this);
    }

    public static TotvsApp get() {
        return instance;
    }

    public SecureStorage secureStorage() {
        return secureStorage;
    }

    public SessionManager session() {
        return sessionManager;
    }

    public DeviceManager devices() {
        return deviceManager;
    }

    public AuthRepository authRepository() {
        return authRepository;
    }

    public DataRepository dataRepository() {
        return dataRepository;
    }

    public PasskeyManager passkeys() {
        return passkeyManager;
    }
}
