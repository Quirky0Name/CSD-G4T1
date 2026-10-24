package com.g4t1.users.auth;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, Instant createdAt) {
}
