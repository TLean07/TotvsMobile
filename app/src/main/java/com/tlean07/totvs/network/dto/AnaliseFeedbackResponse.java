package com.tlean07.totvs.network.dto;

/** One feedback entry of an analysis (GET /api/transcricoes/{id}/feedback). */
public class AnaliseFeedbackResponse {
    public String id;
    public String transcricaoId;
    public String analiseId;
    public boolean util;
    public String ajuste;
    public String createdAt;
}
