package com.g4t1.users;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Tokens for testing the endpoints that need a user token, and the check on the service token User
// Management sends to Storage Management
public final class TestTokens {

    private TestTokens() {
    }

    // what Updating and Research Evaluation mint for themselves: valid, but not a researcher
    public static String serviceToken(String base64Secret) {
        return Jwts.builder().subject("svc:updating").claim("role", "service").signWith(key(base64Secret)).compact();
    }

    // garbage, expired, signed with another key, a sub that isn't a user id, and no sub at all
    public static List<String> badTokens(String base64Secret) {
        SecretKey key = key(base64Secret);
        String user = UUID.randomUUID().toString();
        return List.of(
                "not-a-jwt",
                Jwts.builder().subject(user).expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(key).compact(),
                Jwts.builder().subject(user).signWith(Keys.hmacShaKeyFor(new byte[32])).compact(),
                Jwts.builder().subject("svc:updating").signWith(key).compact(),
                Jwts.builder().claim("role", "user").signWith(key).compact());
    }

    // what Storage Management's /internal/** takes: a service token signed with the shared secret, short-lived
    public static void assertIsServiceToken(String authorization, String base64Secret) {
        assertThat(authorization).startsWith("Bearer ");
        Claims claims = Jwts.parser().verifyWith(key(base64Secret)).build()
                .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
        assertThat(claims.get("role")).isEqualTo("service");
        assertThat(claims.getSubject()).isEqualTo("svc:users");
        assertThat(claims.getExpiration()).isBefore(Date.from(Instant.now().plus(Duration.ofMinutes(10))));
    }

    private static SecretKey key(String base64Secret) {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }
}
