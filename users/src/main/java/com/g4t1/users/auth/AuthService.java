package com.g4t1.users.auth;

import com.g4t1.users.project.StorageClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final TokenService tokens;
    private final StorageClient storage;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();
    // checked when an email has no account, so that answer takes as long as a wrong password
    private final String hashForUnknownEmails = passwords.encode("no account has this password");

    public AuthService(UserRepository users, TokenService tokens, StorageClient storage) {
        this.users = users;
        this.tokens = tokens;
        this.storage = storage;
    }

    public AuthResponse register(RegisterRequest request) {
        String email = normalised(request.email());
        if (users.existsByEmail(email)) {
            throw emailTaken();
        }
        User user = saveNew(new User(email, passwords.encode(request.password())));
        return new AuthResponse(tokens.issue(user.getId()), UserResponse.from(user));
    }

    public AuthResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(normalised(request.email()));
        String hash = found.map(User::getPasswordHash).orElse(hashForUnknownEmails);
        boolean passwordMatches = passwords.matches(request.password(), hash);
        // one answer for an unknown email and a wrong password, so login never shows which emails have accounts
        if (found.isEmpty() || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        User user = found.get();
        return new AuthResponse(tokens.issue(user.getId()), UserResponse.from(user));
    }

    public void deleteAccount(UUID userId, DeleteAccountRequest request) {
        User user = users.findById(userId).orElseThrow();
        if (!passwords.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong password");
        }
        storage.clearUser(userId);
        users.deleteById(userId);
    }

    // another registration of the same email can be saved between the check and this insert
    private User saveNew(User user) {
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
    }

    // one account per address whatever its letter case; ROOT so the server's locale can't change it
    private static String normalised(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
