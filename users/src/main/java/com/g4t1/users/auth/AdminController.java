package com.g4t1.users.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Dev mode only. Under /auth so the Vite proxy and Caddy already route it.
@RestController
@ConditionalOnBooleanProperty("users.dev-mode")
@RequestMapping("/auth/admin")
public class AdminController {

    private final UserRepository users;

    public AdminController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/users")
    public UserListResponse listUsers() {
        return new UserListResponse(users.findByEmailNotOrderByCreatedAtDesc(AdminAccount.EMAIL).stream()
                .map(UserResponse::from)
                .toList());
    }

    public record UserListResponse(List<UserResponse> users) {
    }
}
