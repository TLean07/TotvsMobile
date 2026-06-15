package com.tlean07.totvs.network;

import com.google.gson.Gson;
import com.tlean07.totvs.network.dto.ApiError;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import retrofit2.Response;

/** Turns exceptions and error bodies into friendly, user-facing messages (pt-BR). */
public final class ErrorMapper {

    private static final Gson GSON = new Gson();

    private ErrorMapper() {
    }

    public static String fromThrowable(Throwable t) {
        if (t instanceof UnknownHostException) {
            return "Sem conexão. Verifique sua internet e tente novamente.";
        }
        if (t instanceof SocketTimeoutException) {
            return "O servidor demorou para responder. Tente novamente.";
        }
        if (t instanceof IOException) {
            return "Falha de rede. Tente novamente.";
        }
        return "Algo deu errado. Tente novamente.";
    }

    public static String fromResponse(Response<?> response) {
        // Try to read the backend's {"error": "..."} shape.
        try {
            if (response.errorBody() != null) {
                String body = response.errorBody().string();
                ApiError parsed = GSON.fromJson(body, ApiError.class);
                if (parsed != null && parsed.error != null && !parsed.error.isBlank()) {
                    return parsed.error;
                }
            }
        } catch (Exception ignored) {
            // fall through to status-based message
        }
        switch (response.code()) {
            case 401:
                return "Sessão expirada. Faça login novamente.";
            case 403:
                return "Acesso negado.";
            case 404:
                return "Não encontrado.";
            case 408:
                return "Tempo de requisição esgotado. Tente novamente.";
            case 429:
                return "Muitas tentativas. Aguarde um momento.";
            case 503:
                return "Serviço indisponível no momento. Tente novamente em instantes.";
            default:
                if (response.code() >= 500) {
                    return "Servidor indisponível. Tente novamente em instantes.";
                }
                return "Não foi possível concluir a operação.";
        }
    }
}
