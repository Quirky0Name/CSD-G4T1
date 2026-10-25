package com.g4t1.users.auth;

public record AuthResponse(String token, UserResponse user) {
}
