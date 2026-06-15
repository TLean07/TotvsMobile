package com.tlean07.totvs.network.dto;

/** Item of GET /api/users (admin only). */
public class UserDto {
    public String id;
    public String email;
    public String name;
    public String role;
    public boolean active;
    public String createdAt;

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role);
    }
}
