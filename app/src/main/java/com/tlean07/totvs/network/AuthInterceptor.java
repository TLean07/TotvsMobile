package com.tlean07.totvs.network;

import androidx.annotation.NonNull;

import com.tlean07.totvs.storage.SessionManager;

import java.io.IOException;
import java.util.UUID;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches the in-memory access token as {@code Authorization: Bearer ...},
 * plus a per-request id and the stable device public id (for CAS auditing).
 *
 * It deliberately never sets an {@code Origin} header: the backend's
 * OriginValidationService only rejects requests whose Origin is present and not
 * allow-listed, so a native client with no Origin is accepted as expected.
 */
public class AuthInterceptor implements Interceptor {

    private final SessionManager session;
    private final String deviceId;

    public AuthInterceptor(SessionManager session, String deviceId) {
        this.session = session;
        this.deviceId = deviceId;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        Request.Builder builder = original.newBuilder()
                .header("X-Request-ID", UUID.randomUUID().toString())
                .header("X-Device-Id", deviceId)
                .header("Accept", "application/json");

        String token = session.getAccessToken();
        if (token != null && original.header("Authorization") == null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return chain.proceed(builder.build());
    }
}
