package com.tlean07.totvs.network;

import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.network.dto.CreateUserRequest;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.network.dto.PageResponse;
import com.tlean07.totvs.network.dto.TranscricaoImportResponse;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.network.dto.UserDto;
import com.tlean07.totvs.util.Callback;

import java.util.List;

import retrofit2.Call;

/**
 * Read/write use-cases for the dashboard, transcriptions (AI) and alerts.
 *
 * Reads go through {@link ResponseCache}: a fresh cached response is delivered immediately
 * without touching the network (and therefore the database); {@code forceRefresh} bypasses
 * the cache for pull-to-refresh. Writes invalidate the entries they affect.
 */
public class DataRepository {

    private static final long DEFAULT_TTL_MS = ResponseCache.DEFAULT_TTL_MS;
    private static final long INSIGHTS_TTL_MS = 5 * 60 * 1000L;

    private static final String KEY_DASHBOARD = "dashboard";
    private static final String KEY_TRANSCRICOES = "transcricoes";
    private static final String KEY_TRANSCRICAO = "transcricao:";
    private static final String KEY_INSIGHTS = "insights";
    private static final String KEY_ALERTAS = "alertas";
    private static final String KEY_USERS = "users";

    private final ApiService api;
    private final ResponseCache cache = ResponseCache.get();

    public DataRepository(ApiService api) {
        this.api = api;
    }

    // --- Dashboard ---

    public void dashboard(Callback<DashboardResponse> cb) {
        dashboard(false, cb);
    }

    public void dashboard(boolean forceRefresh, Callback<DashboardResponse> cb) {
        cached(KEY_DASHBOARD, DEFAULT_TTL_MS, forceRefresh, api.dashboard(), cb);
    }

    // --- Transcricoes ---

    public void transcricoes(Callback<List<TranscricaoResponse>> cb) {
        transcricoes(false, cb);
    }

    public void transcricoes(boolean forceRefresh, Callback<List<TranscricaoResponse>> cb) {
        cached(KEY_TRANSCRICOES + ":todas", DEFAULT_TTL_MS, forceRefresh, api.transcricoes(), cb);
    }

    public void transcricoesPaginadas(int page, int size,
                                      Callback<PageResponse<TranscricaoResponse>> cb) {
        transcricoesPaginadas(page, size, false, cb);
    }

    public void transcricoesPaginadas(int page, int size, boolean forceRefresh,
                                      Callback<PageResponse<TranscricaoResponse>> cb) {
        cached(KEY_TRANSCRICOES + ":pagina:" + page + ":" + size, DEFAULT_TTL_MS, forceRefresh,
                api.transcricoesPaginadas(page, size), cb);
    }

    public void insights(boolean forceRefresh, Callback<List<TranscricaoInsightsResponse>> cb) {
        cached(KEY_INSIGHTS, INSIGHTS_TTL_MS, forceRefresh, api.insights(), cb);
    }

    public void transcricao(String id, Callback<TranscricaoResponse> cb) {
        transcricao(id, false, cb);
    }

    public void transcricao(String id, boolean forceRefresh, Callback<TranscricaoResponse> cb) {
        cached(KEY_TRANSCRICAO + id, DEFAULT_TTL_MS, forceRefresh, api.transcricao(id), cb);
    }

    /**
     * Every transcription, fetched in pages of 100 so no single request hits the
     * Render/Oracle latency wall; cached for {@value #INSIGHTS_TTL_MS} ms. Used
     * by the local search/filters and the filtered dashboard.
     */
    public void transcricoesTodas(boolean forceRefresh, Callback<List<TranscricaoResponse>> cb) {
        String key = KEY_TRANSCRICOES + ":todas:paginado";
        if (!forceRefresh) {
            List<TranscricaoResponse> hit = cache.get(key, INSIGHTS_TTL_MS);
            if (hit != null) {
                cb.onResult(NetworkResult.ok(hit));
                return;
            }
        }
        List<TranscricaoResponse> acc = new java.util.ArrayList<>();
        carregarPagina(0, acc, key, cb);
    }

    private void carregarPagina(int page, List<TranscricaoResponse> acc, String key, Callback<List<TranscricaoResponse>> cb) {
        carregarPagina(page, acc, key, cb, 1);
    }

    private void carregarPagina(int page, List<TranscricaoResponse> acc, String key, Callback<List<TranscricaoResponse>> cb, int retries) {
        Calls.execute(api.transcricoesPaginadas(page, 100), result -> {
            if (!result.success || result.data == null) {
                if (retries > 0) {
                    // Render/Oracle hiccups on one page must not throw the whole list away.
                    carregarPagina(page, acc, key, cb, retries - 1);
                    return;
                }
                cb.onResult(result.networkError ? NetworkResult.networkError(result.errorMessage) : NetworkResult.error(result.errorMessage));
                return;
            }
            if (result.data.content != null) {
                acc.addAll(result.data.content);
            }
            if (result.data.last || result.data.content == null || result.data.content.isEmpty() || page > 50) {
                cache.put(key, acc);
                cb.onResult(NetworkResult.ok(acc));
            } else {
                carregarPagina(page + 1, acc, key, cb);
            }
        });
    }

    public void transcricoesPorCliente(String cliente, Callback<List<TranscricaoResponse>> cb) {
        Calls.execute(api.transcricoesPorCliente(cliente), cb);
    }

    /** Raw call for background workers (synchronous). */
    public ApiService api() {
        return api;
    }

    public void feedbacks(String transcricaoId, Callback<List<com.tlean07.totvs.network.dto.AnaliseFeedbackResponse>> cb) {
        Calls.execute(api.feedbacks(transcricaoId), cb);
    }

    public void registrarFeedback(String transcricaoId, boolean util, String ajuste,
                                  Callback<com.tlean07.totvs.network.dto.AnaliseFeedbackResponse> cb) {
        Calls.execute(api.registrarFeedback(transcricaoId,
                new com.tlean07.totvs.network.dto.AnaliseFeedbackRequest(util, ajuste)), cb);
    }

    /** Re-queues a failed analysis; every derived view is invalidated. */
    public void reprocessar(String transcricaoId, Callback<TranscricaoResponse> cb) {
        Calls.execute(api.reprocessar(transcricaoId), result -> {
            if (result.success) {
                invalidateTranscricaoData();
            }
            cb.onResult(result);
        });
    }

    public void criarTranscricao(TranscricaoRequest request, Callback<TranscricaoResponse> cb) {
        Calls.execute(api.criarTranscricao(request), result -> {
            if (result.success) {
                invalidateTranscricaoData();
            }
            cb.onResult(result);
        });
    }

    /** Uploads a TOTVS export JSON (same as the web "Importar JSON" action). */
    public void importTotvsJson(byte[] bytes, String fileName, Callback<TranscricaoImportResponse> cb) {
        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                bytes, okhttp3.MediaType.parse("application/json"));
        okhttp3.MultipartBody.Part part = okhttp3.MultipartBody.Part.createFormData(
                "file", fileName != null ? fileName : "totvs.json", body);
        Calls.execute(api.importTotvsJson(part, 25, 0), result -> {
            if (result.success) {
                invalidateTranscricaoData();
            }
            cb.onResult(result);
        });
    }

    /** Uploads a recorded meeting for server-side transcription (Parakeet) + analysis. */
    public void importarAudio(java.io.File audio, String cliente, String vendedor, String participantes,
                              Callback<TranscricaoResponse> cb) {
        String name = audio.getName().toLowerCase(java.util.Locale.ROOT);
        String mime = name.endsWith(".m4a") ? "audio/mp4" : name.endsWith(".mp3") ? "audio/mpeg" : "audio/wav";
        okhttp3.RequestBody body = okhttp3.RequestBody.create(audio, okhttp3.MediaType.parse(mime));
        okhttp3.MultipartBody.Part part = okhttp3.MultipartBody.Part.createFormData("file", audio.getName(), body);
        Calls.execute(api.importarAudio(part, textPart(cliente), textPart(vendedor), textPart(participantes)), result -> {
            if (result.success) {
                invalidateTranscricaoData();
            }
            cb.onResult(result);
        });
    }

    private static okhttp3.RequestBody textPart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return okhttp3.RequestBody.create(value, okhttp3.MediaType.parse("text/plain; charset=utf-8"));
    }

    // --- Alertas ---

    public void alertas(Callback<List<AlertaResponse>> cb) {
        alertas(false, cb);
    }

    public void alertas(boolean forceRefresh, Callback<List<AlertaResponse>> cb) {
        cached(KEY_ALERTAS, DEFAULT_TTL_MS, forceRefresh, api.alertas(), cb);
    }

    public void marcarAlertaVisto(String id, Callback<AlertaResponse> cb) {
        Calls.execute(api.marcarAlertaVisto(id), result -> {
            if (result.success) {
                cache.invalidate(KEY_ALERTAS);
                cache.invalidate(KEY_DASHBOARD);
            }
            cb.onResult(result);
        });
    }

    // --- Administracao de usuarios ---

    public void users(Callback<List<UserDto>> cb) {
        users(false, cb);
    }

    public void users(boolean forceRefresh, Callback<List<UserDto>> cb) {
        cached(KEY_USERS, DEFAULT_TTL_MS, forceRefresh, api.users(), cb);
    }

    public void criarUsuario(CreateUserRequest request, Callback<UserDto> cb) {
        Calls.execute(api.criarUsuario(request), result -> {
            if (result.success) {
                cache.invalidate(KEY_USERS);
            }
            cb.onResult(result);
        });
    }

    public void setUsuarioAtivo(String id, boolean active, Callback<UserDto> cb) {
        Calls.execute(active ? api.ativarUsuario(id) : api.desativarUsuario(id), result -> {
            if (result.success) {
                cache.invalidate(KEY_USERS);
            }
            cb.onResult(result);
        });
    }

    /** Drops everything the app remembers; used on logout. */
    public void clearCache() {
        cache.clear();
    }

    // --- internals ---

    /**
     * Delivers a fresh cached value synchronously (the callers are already on the main
     * thread) or executes the call and stores a successful body under {@code key}.
     */
    private <T> void cached(String key, long ttlMs, boolean forceRefresh, Call<T> call, Callback<T> cb) {
        if (!forceRefresh) {
            T hit = cache.get(key, ttlMs);
            if (hit != null) {
                cb.onResult(NetworkResult.ok(hit));
                return;
            }
        }
        Calls.execute(call, result -> {
            if (result.success && result.data != null) {
                cache.put(key, result.data);
            }
            cb.onResult(result);
        });
    }

    /** Public hook for background senders (offline queue). */
    public void invalidateTranscricoes() {
        invalidateTranscricaoData();
    }

    /** Any change to transcriptions moves dashboard numbers, lists, insights and alerts. */
    private void invalidateTranscricaoData() {
        cache.invalidate(KEY_DASHBOARD);
        cache.invalidate(KEY_TRANSCRICOES);
        cache.invalidate(KEY_TRANSCRICAO);
        cache.invalidate(KEY_INSIGHTS);
        cache.invalidate(KEY_ALERTAS);
    }
}
