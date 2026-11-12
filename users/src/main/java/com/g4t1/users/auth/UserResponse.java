package com.g4t1.users.auth;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String role, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.isAdmin() ? "admin" : "user", user.getCreatedAt());
    }
}
