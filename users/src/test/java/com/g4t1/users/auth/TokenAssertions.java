package com.g4t1.users.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import java.net.HttpCookie;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class TokenAssertions {

    private TokenAssertions() {
    }

    // checked the way the contract describes it: HS256 (the Python services accept nothing else) with
    // the base64-decoded JWT_SECRET, sub the user's id, 1 hour to live, and no service role
    static void assertIsTokenFor(String token, String userId, String base64Secret) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret)))
                .build()
                .parseSignedClaims(token);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(jws.getPayload().getSubject()).isEqualTo(userId);
        assertThat(jws.getPayload().get("role")).isNull();
        assertThat(jws.getPayload().getExpiration())
                .isCloseTo(Date.from(Instant.now().plus(Duration.ofHours(1))), 60_000);
    }

    // the refresh token's cookie as the contract sets it (docs/CONTRACTS.md, "Auth"); returns its value
    static String assertSetsRefreshCookie(List<String> setCookieHeaders, boolean secure) {
        String header = refreshCookieHeader(setCookieHeaders);
        HttpCookie cookie = HttpCookie.parse(header).getFirst();
        assertThat(cookie.getValue()).matches("[A-Za-z0-9_-]{43}");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/auth");
        assertThat(cookie.getMaxAge()).isEqualTo(604800);
        assertThat(header).containsIgnoringCase("SameSite=Strict");
        assertThat(cookie.getSecure()).isEqualTo(secure);
        return cookie.getValue();
    }

    // a cookie that clears the refresh token: empty, expired at once, on the same path
    static void assertClearsRefreshCookie(List<String> setCookieHeaders) {
        String header = refreshCookieHeader(setCookieHeaders);
        HttpCookie cookie = HttpCookie.parse(header).getFirst();
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getPath()).isEqualTo("/auth");
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    private static String refreshCookieHeader(List<String> setCookieHeaders) {
        assertThat(setCookieHeaders).isNotNull();
        List<String> refresh = setCookieHeaders.stream().filter(h -> h.startsWith("refresh_token=")).toList();
        assertThat(refresh).hasSize(1);
        return refresh.getFirst();
    }
}
