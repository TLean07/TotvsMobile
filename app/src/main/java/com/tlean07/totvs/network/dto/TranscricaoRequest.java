package com.tlean07.totvs.network.dto;

/** Body for POST /api/transcricoes (create + analyse). */
public class TranscricaoRequest {
    public String textoOriginal;
    public String cliente;
    public String vendedor;
    public String participantes;

    public TranscricaoRequest(String textoOriginal, String cliente, String vendedor, String participantes) {
        this.textoOriginal = textoOriginal;
        this.cliente = cliente;
        this.vendedor = vendedor;
        this.participantes = participantes;
    }
}
