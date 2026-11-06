package com.g4t1.users.auth;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

// The dev mode admin (docs/CONTRACTS.md, "Admin token"). It runs before the server takes requests, so a
// restart with dev mode off never answers the known password.
@Component
public class AdminAccount implements SmartInitializingSingleton {

    static final String EMAIL = "admin";
    private static final String PASSWORD = "12345679";

    private final UserRepository users;
    private final boolean devMode;

    public AdminAccount(UserRepository users, @Value("${users.dev-mode}") boolean devMode) {
        this.users = users;
        this.devMode = devMode;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!devMode) {
            users.deleteByEmail(EMAIL);
        } else if (!users.existsByEmail(EMAIL)) {
            users.saveAndFlush(new User(EMAIL, new BCryptPasswordEncoder().encode(PASSWORD)));
        }
    }
}
