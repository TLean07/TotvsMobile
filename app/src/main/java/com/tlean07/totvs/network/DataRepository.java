package com.tlean07.totvs.network;

import com.tlean07.totvs.network.dto.AlertaResponse;
import com.tlean07.totvs.network.dto.CreateUserRequest;
import com.tlean07.totvs.network.dto.DashboardResponse;
import com.tlean07.totvs.network.dto.TranscricaoRequest;
import com.tlean07.totvs.network.dto.TranscricaoResponse;
import com.tlean07.totvs.network.dto.UserDto;
import com.tlean07.totvs.util.Callback;

import java.util.List;

/** Read/write use-cases for the dashboard, transcriptions (AI) and alerts. */
public class DataRepository {

    private final ApiService api;

    public DataRepository(ApiService api) {
        this.api = api;
    }

    public void dashboard(Callback<DashboardResponse> cb) {
        Calls.execute(api.dashboard(), cb);
    }

    public void transcricoes(Callback<List<TranscricaoResponse>> cb) {
        Calls.execute(api.transcricoes(), cb);
    }

    public void transcricao(String id, Callback<TranscricaoResponse> cb) {
        Calls.execute(api.transcricao(id), cb);
    }

    public void criarTranscricao(TranscricaoRequest request, Callback<TranscricaoResponse> cb) {
        Calls.execute(api.criarTranscricao(request), cb);
    }

    public void alertas(Callback<List<AlertaResponse>> cb) {
        Calls.execute(api.alertas(), cb);
    }

    public void marcarAlertaVisto(String id, Callback<AlertaResponse> cb) {
        Calls.execute(api.marcarAlertaVisto(id), cb);
    }

    // --- Administração de usuários ---

    public void users(Callback<List<UserDto>> cb) {
        Calls.execute(api.users(), cb);
    }

    public void criarUsuario(CreateUserRequest request, Callback<UserDto> cb) {
        Calls.execute(api.criarUsuario(request), cb);
    }

    public void setUsuarioAtivo(String id, boolean active, Callback<UserDto> cb) {
        Calls.execute(active ? api.ativarUsuario(id) : api.desativarUsuario(id), cb);
    }
}
