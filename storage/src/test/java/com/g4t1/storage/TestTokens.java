package com.g4t1.storage;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.UUID;

// Mints tokens the way User Management and Updating do, signed with the key in application-test.yml
public final class TestTokens {

    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode("c3RvcmFnZS10ZXN0LXNlY3JldC1ub3QtZm9yLXByb2Qh"));

    private TestTokens() {
    }

    public static String user(UUID userId) {
        return "Bearer " + Jwts.builder().subject(userId.toString()).signWith(KEY).compact();
    }

    public static String service() {
        return "Bearer " + Jwts.builder().subject("svc:updating").claim("role", "service").signWith(KEY).compact();
    }
}
