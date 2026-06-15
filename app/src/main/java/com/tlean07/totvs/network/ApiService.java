package com.tlean07.totvs.network;

import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.network.dto.AuthResponse;
import com.tlean07.totvs.network.dto.CreateUserRequest;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.network.dto.LoginRequest;
import com.tlean07.totvs.network.dto.PageResponse;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoImportResponse;
import com.tlean07.totvs.network.dto.TranscricaoInsightsResponse;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.network.dto.UserDto;

import java.util.List;

import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;

/** Retrofit description of the shared backend (same endpoints the web app uses). */
public interface ApiService {

    @POST("auth/login")
    Call<AuthResponse> login(@Body LoginRequest body);

    @POST("auth/refresh")
    Call<AuthResponse> refresh();

    @POST("auth/logout")
    Call<Void> logout();

    @GET("dashboard")
    Call<DashboardResponse> dashboard();

    @GET("transcricoes")
    Call<List<TranscricaoResponse>> transcricoes();

    @GET("transcricoes/paged")
    Call<PageResponse<TranscricaoResponse>> transcricoesPaginadas(
            @Query("page") int page,
            @Query("size") int size);

    @GET("transcricoes/insights")
    Call<List<TranscricaoInsightsResponse>> insights();

    @GET("transcricoes/{id}")
    Call<TranscricaoResponse> transcricao(@Path("id") String id);

    @POST("transcricoes")
    Call<TranscricaoResponse> criarTranscricao(@Body TranscricaoRequest body);

    @Multipart
    @POST("transcricoes/import/totvs")
    Call<TranscricaoImportResponse> importTotvsJson(
            @Part MultipartBody.Part file,
            @Query("limit") int limit,
            @Query("offset") int offset);

    @GET("alertas")
    Call<List<AlertaResponse>> alertas();

    @GET("alertas/novos")
    Call<List<AlertaResponse>> alertasNovos();

    @PATCH("alertas/{id}/visto")
    Call<AlertaResponse> marcarAlertaVisto(@Path("id") String id);

    // --- Administração de usuários (somente ADMIN) ---

    @GET("users")
    Call<List<UserDto>> users();

    @POST("users")
    Call<UserDto> criarUsuario(@Body CreateUserRequest body);

    @PATCH("users/{id}/activate")
    Call<UserDto> ativarUsuario(@Path("id") String id);

    @PATCH("users/{id}/deactivate")
    Call<UserDto> desativarUsuario(@Path("id") String id);
}
