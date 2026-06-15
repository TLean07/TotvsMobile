package com.tlean07.totvs.network;

import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.network.dto.AuthResponse;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.network.dto.LoginRequest;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoResponse;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;

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

    @GET("transcricoes/{id}")
    Call<TranscricaoResponse> transcricao(@Path("id") String id);

    @POST("transcricoes")
    Call<TranscricaoResponse> criarTranscricao(@Body TranscricaoRequest body);

    @GET("alertas")
    Call<List<AlertaResponse>> alertas();

    @GET("alertas/novos")
    Call<List<AlertaResponse>> alertasNovos();

    @PATCH("alertas/{id}/visto")
    Call<AlertaResponse> marcarAlertaVisto(@Path("id") String id);
}
