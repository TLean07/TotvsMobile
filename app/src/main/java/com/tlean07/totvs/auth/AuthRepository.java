package com.tlean07.totvs.auth;

import com.tlean07.totvs.cas.DeviceManager;
import com.tlean07.totvs.network.ApiService;
import com.tlean07.totvs.network.ResponseCache;
import com.tlean07.totvs.network.Calls;
import com.tlean07.totvs.network.NetworkResult;
import com.tlean07.totvs.network.dto.AuthResponse;
import com.tlean07.totvs.network.dto.LoginRequest;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.util.AppExecutors;
import com.tlean07.totvs.util.Callback;

import retrofit2.Response;

/**
 * Authentication use-cases on top of the shared backend. Reuses the same
 * /api/auth/login, /refresh and /logout endpoints as the web client.
 */
public class AuthRepository {

    private final ApiService api;
    private final SessionManager session;
    private final DeviceManager deviceManager;

    public AuthRepository(ApiService api, SessionManager session, DeviceManager deviceManager) {
        this.api = api;
        this.session = session;
        this.deviceManager = deviceManager;
    }

    public void login(String email, String password, Callback<AuthResponse> callback) {
        AppExecutors.io(() -> {
            try {
                Response<AuthResponse> response =
                        api.login(new LoginRequest(email.trim(), password)).execute();
                if (response.isSuccessful() && response.body() != null) {
                    AuthResponse body = response.body();
                    session.setSession(body);
                    // Bind this device to the authenticated user (local CAS record).
                    if (body.user != null) {
                        deviceManager.bindToUser(body.user);
                    }
                    AppExecutors.main(() -> callback.onResult(NetworkResult.ok(body)));
                } else {
                    String message = com.tlean07.totvs.network.ErrorMapper.fromResponse(response);
                    AppExecutors.main(() -> callback.onResult(NetworkResult.error(message)));
                }
            } catch (Exception e) {
                String message = com.tlean07.totvs.network.ErrorMapper.fromThrowable(e);
                AppExecutors.main(() -> callback.onResult(NetworkResult.error(message)));
            }
        });
    }

    /**
     * Silent re-login using the stored refresh cookie. Used by the splash screen
     * and the lock screen to restore a session without credentials.
     */
    public void silentRefresh(Callback<AuthResponse> callback) {
        if (!session.hasPersistentSession()) {
            callback.onResult(NetworkResult.error("Sem sessão salva"));
            return;
        }
        AppExecutors.io(() -> {
            try {
                Response<AuthResponse> response = api.refresh().execute();
                if (response.isSuccessful() && response.body() != null) {
                    session.setSession(response.body());
                    AppExecutors.main(() -> callback.onResult(NetworkResult.ok(response.body())));
                } else {
                    session.clear();
                ResponseCache.get().clear();
                    AppExecutors.main(() -> callback.onResult(NetworkResult.error("Sessão expirada")));
                }
            } catch (Exception e) {
                AppExecutors.main(() -> callback.onResult(
                        NetworkResult.error(com.tlean07.totvs.network.ErrorMapper.fromThrowable(e))));
            }
        });
    }

    public void logout(Callback<Void> callback) {
        AppExecutors.io(() -> {
            try {
                api.logout().execute();
            } catch (Exception ignored) {
                // best-effort: clear locally regardless of network outcome
            } finally {
                session.clear();
                ResponseCache.get().clear();
                AppExecutors.main(() -> callback.onResult(NetworkResult.ok(null)));
            }
        });
    }
}
