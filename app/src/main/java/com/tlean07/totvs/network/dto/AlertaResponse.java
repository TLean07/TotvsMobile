package com.tlean07.totvs.network.dto;

/** Subset of /api/alertas items. */
public class AlertaResponse {
    public String id;
    public String tipo;
    public String titulo;
    public String categoria;
    public String mensagem;
    public String recomendacao;
    public Integer ocorrencias;
    public String prioridade;
    public String status;
    public String dataCriacao;
}
