package com.tlean07.totvs.network.dto;

import java.util.List;
import java.util.Map;

/** Subset of GET /api/dashboard used by the mobile dashboard. */
public class DashboardResponse {
    public long totalTranscricoes;
    public long transcricoesProcessadas;
    public long totalOportunidades;
    public long totalRiscos;
    public long alertasNovos;
    public long churnsPotenciais;
    public long mencoesCriticas;
    public long partesNegativas;
    public long partesPositivas;
    public long partesNeutras;
    public Map<String, Long> sentimentoDistribuicao;
    public List<AlertaResponse> alertasRecentes;
    public List<TranscricaoResponse> ultimasTranscricoes;
}
