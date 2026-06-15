package com.tlean07.totvs.network;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tlean07.totvs.network.dto.AuthResponse;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.util.Logger;

import java.io.IOException;

import okhttp3.Authenticator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Reactive token refresh. When any authenticated call returns 401, OkHttp calls
 * {@link #authenticate} once: we hit {@code POST /api/auth/refresh} (the refresh
 * cookie is attached automatically by {@link PersistentCookieJar}), store the
 * new access token and retry the original request with it.
 *
 * Refreshes are serialised so concurrent 401s trigger only one network refresh.
 * If the refresh itself fails (expired/revoked), the session is cleared and a
 * forced-logout event is raised.
 */
public class TokenAuthenticator implements Authenticator {

    private static final String TAG = "TokenAuthenticator";
    private static final MediaType JSON = MediaType.get("application/json");

    private final SessionManager session;
    private final String baseUrl;
    private final OkHttpClient refreshClient;
    private final Gson gson = new Gson();
    private final Object lock = new Object();

    public TokenAuthenticator(SessionManager session, String baseUrl, OkHttpClient refreshClient) {
        this.session = session;
        this.baseUrl = baseUrl;
        this.refreshClient = refreshClient;
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, Response response) throws IOException {
        if (responseCount(response) >= 2) {
            return null; // already retried once — stop.
        }

        // Never try to refresh on the auth endpoints themselves. A 401 from
        // /auth/login means "wrong credentials" and must surface as-is.
        String path = response.request().url().encodedPath();
        if (path.contains("/auth/login") || path.contains("/auth/refresh") || path.contains("/auth/logout")) {
            return null;
        }

        String failedToken = tokenFromRequest(response.request());

        synchronized (lock) {
            String current = session.getAccessToken();
            // Another thread may have refreshed while we waited on the lock.
            if (current != null && !current.equals(failedToken)) {
                return retryWith(response, current);
            }

            if (!session.hasPersistentSession()) {
                forceLogout();
                return null;
            }

            AuthResponse refreshed = doRefresh();
            if (refreshed == null || refreshed.accessToken == null) {
                forceLogout();
                return null;
            }
            session.setSession(refreshed);
            return retryWith(response, refreshed.accessToken);
        }
    }

    @Nullable
    private AuthResponse doRefresh() {
        Request request = new Request.Builder()
                .url(baseUrl + "auth/refresh")
                .post(RequestBody.create(new byte[0], JSON))
                .header("Accept", "application/json")
                .build();
        try (Response res = refreshClient.newCall(request).execute()) {
            if (!res.isSuccessful() || res.body() == null) {
                Logger.w(TAG, "Refresh failed with code " + res.code());
                return null;
            }
            return gson.fromJson(res.body().string(), AuthResponse.class);
        } catch (IOException e) {
            Logger.e(TAG, "Refresh request error", e);
            return null;
        }
    }

    private void forceLogout() {
        session.clear();
        AuthEvents.notifySessionExpired();
    }

    private Request retryWith(Response response, String token) {
        return response.request().newBuilder()
                .header("Authorization", "Bearer " + token)
                .build();
    }

    @Nullable
    private String tokenFromRequest(Request request) {
        String header = request.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private int responseCount(Response response) {
        int count = 1;
        Response prior = response.priorResponse();
        while (prior != null) {
            count++;
            prior = prior.priorResponse();
        }
        return count;
    }
}
