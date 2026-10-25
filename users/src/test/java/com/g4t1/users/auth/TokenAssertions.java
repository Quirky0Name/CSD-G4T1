package com.g4t1.users.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

final class TokenAssertions {

    private TokenAssertions() {
    }

    // checked the way the contract describes it: HS256 (the Python services accept nothing else) with
    // the base64-decoded JWT_SECRET, sub the user's id, 24 hours to live, and no service role
    static void assertIsTokenFor(String token, String userId, String base64Secret) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret)))
                .build()
                .parseSignedClaims(token);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(jws.getPayload().getSubject()).isEqualTo(userId);
        assertThat(jws.getPayload().get("role")).isNull();
        assertThat(jws.getPayload().getExpiration())
                .isCloseTo(Date.from(Instant.now().plus(Duration.ofHours(24))), 60_000);
    }
}
