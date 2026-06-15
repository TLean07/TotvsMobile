package com.tlean07.totvs.network;

import com.tlean07.totvs.BuildConfig;
import com.tlean07.totvs.storage.SessionManager;
import com.tlean07.totvs.util.Logger;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Builds the shared OkHttp + Retrofit stack:
 * <ul>
 *   <li>a {@link PersistentCookieJar} (encrypted) shared by every client,</li>
 *   <li>a no-authenticator "refresh client" used only to renew tokens,</li>
 *   <li>the main client with {@link AuthInterceptor} + {@link TokenAuthenticator}.</li>
 * </ul>
 * Timeouts are generous enough for the AI analysis path (backend → Hugging Face).
 */
public class ApiClient {

    private final ApiService apiService;
    private final PersistentCookieJar cookieJar;

    public ApiClient(String baseUrl, PersistentCookieJar cookieJar, SessionManager session, String deviceId) {
        this.cookieJar = cookieJar;

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(
                message -> Logger.d("HTTP", message));
        // Never log bodies/headers in release; even in debug headers are sanitised by Logger.
        logging.setLevel(BuildConfig.DEBUG
                ? HttpLoggingInterceptor.Level.BASIC
                : HttpLoggingInterceptor.Level.NONE);

        OkHttpClient refreshClient = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        TokenAuthenticator authenticator = new TokenAuthenticator(session, baseUrl, refreshClient);

        OkHttpClient client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .addInterceptor(new AuthInterceptor(session, deviceId))
                .addInterceptor(logging)
                .authenticator(authenticator)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)   // AI analysis can be slow
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        this.apiService = retrofit.create(ApiService.class);
    }

    public ApiService api() {
        return apiService;
    }

    public PersistentCookieJar cookieJar() {
        return cookieJar;
    }
}
