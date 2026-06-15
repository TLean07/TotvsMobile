package com.tlean07.totvs.network.dto;

/** Body for POST /api/auth/login — mirrors the web client exactly. */
public class LoginRequest {
    public final String email;
    public final String password;

    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }
}
