package com.tlean07.totvs.network.dto;

/** Subset of /api/transcricoes items used by the list and detail screens. */
public class TranscricaoResponse {
    public String id;
    public String cliente;
    public String vendedor;
    public String participantes;
    public String origemTranscricao;
    public String status;
    public String dataReuniao;
    public String dataCriacao;
    public String resumoExecutivo;
}
