package com.tlean07.totvs.network.dto;

/** Body for POST /api/transcricoes/{id}/feedback (feeds the LeanIA context). */
public class AnaliseFeedbackRequest {
    public boolean util;
    public String ajuste;

    public AnaliseFeedbackRequest(boolean util, String ajuste) {
        this.util = util;
        this.ajuste = ajuste;
    }
}
