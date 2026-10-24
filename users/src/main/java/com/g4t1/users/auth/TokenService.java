package com.g4t1.users.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

// The user JWT every other service checks on its own (docs/CONTRACTS.md, "Auth")
@Service
public class TokenService {

    private static final Duration LIFETIME = Duration.ofHours(24);

    private final SecretKey key;

    public TokenService(@Value("${users.jwt-secret}") String base64Secret) {
        // every service base64-decodes JWT_SECRET the same way
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .expiration(Date.from(now.plus(LIFETIME)))
                // HS256 whatever the key's length: the contract says so, and the Python services check for nothing else
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
