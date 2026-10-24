package com.g4t1.users.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;

public record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(min = 8) String password) {

    // BCrypt can't hash past 72 bytes, so a longer password gets a 400 here instead of an error from the encoder
    @AssertTrue(message = "must be at most 72 bytes")
    public boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
