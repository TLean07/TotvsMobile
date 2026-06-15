package com.tlean07.totvs.network.dto;

/** Body for POST /api/users — matches the backend validation (password min 12). */
public class CreateUserRequest {
    public final String name;
    public final String email;
    public final String password;
    public final String role;

    public CreateUserRequest(String name, String email, String password, String role) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
    }
}
