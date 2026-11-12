package com.g4t1.users.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh tokens (docs/CONTRACTS.md, "Auth"): 32 random bytes, sent only as an HttpOnly cookie,
 * valid a week from issue and used once. Refreshing marks the token used and issues a new one, so
 * the week slides with use. Only a token's SHA-256 hash is stored.
 */
@Service
public class RefreshTokenService {

    public static final String COOKIE = "refresh_token";
    static final Duration LIFETIME = Duration.ofDays(7);
    // the cookie goes only to /auth/*, where refresh and logout are
    private static final String COOKIE_PATH = "/auth";

    private final RefreshTokenRepository tokens;
    private final boolean secureCookie;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens,
                               @Value("${users.refresh-cookie-secure}") boolean secureCookie) {
        this.tokens = tokens;
        this.secureCookie = secureCookie;
    }

    /** A new token for the account; the returned value is the only copy of it. */
    public String issue(UUID userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.saveAndFlush(new RefreshToken(userId, hash(token), Instant.now().plus(LIFETIME)));
        return token;
    }

    /**
     * Uses the token: its account's id when it's known, unexpired and unused, and marking it used
     * succeeded. Empty for anything else, including losing a race to another use of the same token.
     */
    public Optional<UUID> use(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return tokens.findByTokenHash(hash(token))
                .filter(stored -> stored.getUsedAt() == null && stored.getExpiresAt().isAfter(now))
                .filter(stored -> tokens.markUsed(stored.getId(), now) == 1)
                .map(RefreshToken::getUserId);
    }

    // sign out: the token stops working, whether or not it was still valid
    public void revoke(String token) {
        if (token != null && !token.isEmpty()) {
            tokens.markUsedByHash(hash(token), Instant.now());
        }
    }

    public ResponseCookie cookie(String token) {
        return cookieWith(token, LIFETIME);
    }

    public ResponseCookie clearedCookie() {
        return cookieWith("", Duration.ZERO);
    }

    private ResponseCookie cookieWith(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
