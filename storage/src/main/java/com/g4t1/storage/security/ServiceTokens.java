package com.g4t1.storage.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints Storage Management's own service token (sub svc:storage, role service), signed with JWT_SECRET
 * like every other service's, for its one outbound call: the feedback nudge to Research Evaluation.
 */
@Component
public class ServiceTokens {

    static final String SUBJECT = "svc:storage";
    static final Duration LIFETIME = Duration.ofMinutes(5);

    private final SecretKey key;

    public ServiceTokens(@Value("${storage.jwt-secret}") String base64Secret) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }

    // a fresh token for one request
    public String mint() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(SUBJECT)
                .claim(JwtAuthFilter.ROLE_CLAIM, JwtAuthFilter.SERVICE_ROLE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(LIFETIME)))
                // HS256 forced: the key's length would otherwise pick it, and a 48+ byte JWT_SECRET would give
                // HS384 or HS512, which the Python services reject (docs/DECISIONS.md)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
