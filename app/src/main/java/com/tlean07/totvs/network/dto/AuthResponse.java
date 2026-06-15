package com.tlean07.totvs.network.dto;

/** Response from /api/auth/login and /api/auth/refresh. */
public class AuthResponse {
    public String accessToken;
    public long expiresInSeconds;
    public AuthUser user;
}
