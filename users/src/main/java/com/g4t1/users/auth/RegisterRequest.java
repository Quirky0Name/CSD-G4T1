package com.g4t1.users.auth;

import jakarta.validation.constraints.Email;

public record RegisterRequest(@Email String email, String password) {
}
