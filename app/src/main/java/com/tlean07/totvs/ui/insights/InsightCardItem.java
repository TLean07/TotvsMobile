package com.tlean07.totvs.ui.insights;

import java.util.List;

public class InsightCardItem {
    public final String id;
    public final String transcricaoId;
    public final String cliente;
    public final String categoria;
    public final String tipo;
    public final String badge;
    public final int ocorrencias;
    public final List<String> evidencias;
    public final String recomendacao;
    public final boolean legado;
    public final boolean risco;

    public InsightCardItem(String id, String transcricaoId, String cliente, String categoria,
                           String tipo, String badge, int ocorrencias, List<String> evidencias,
                           String recomendacao, boolean legado, boolean risco) {
        this.id = id;
        this.transcricaoId = transcricaoId;
        this.cliente = cliente;
        this.categoria = categoria;
        this.tipo = tipo;
        this.badge = badge;
        this.ocorrencias = ocorrencias;
        this.evidencias = evidencias;
        this.recomendacao = recomendacao;
        this.legado = legado;
        this.risco = risco;
    }
}
