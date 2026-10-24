package com.g4t1.users.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository users;
    private final TokenService tokens;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();

    public AuthService(UserRepository users, TokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // one account per address whatever its letter case; ROOT so the server's locale can't change it
        String email = request.email().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        User user = users.save(new User(email, passwords.encode(request.password())));
        return new AuthResponse(tokens.issue(user.getId()), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // one answer for an unknown email and a wrong password, so login never shows which emails have accounts
        User user = users.findByEmail(request.email().toLowerCase(Locale.ROOT))
                .filter(found -> passwords.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password"));
        return new AuthResponse(tokens.issue(user.getId()), UserResponse.from(user));
    }
}
