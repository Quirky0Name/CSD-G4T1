package com.g4t1.users.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;

    public AuthService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // one account per address whatever its letter case; ROOT so the server's locale can't change it
        String email = request.email().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        return UserResponse.from(users.save(new User(email)));
    }
}
