package com.tlean07.totvs.network.dto;

import java.io.Serializable;

/** The user object embedded in AuthResponse (id, email, name, role). */
public class AuthUser implements Serializable {
    public String id;
    public String email;
    public String name;
    public String role;

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
