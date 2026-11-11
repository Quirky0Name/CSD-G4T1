package com.g4t1.users.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;
    private final RefreshTokenService refreshTokens;

    public AuthController(AuthService auth, RefreshTokenService refreshTokens) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
    }

    // register, login and refresh answer with the access token in the body and the refresh token as a
    // cookie only, so no script on the page can read it

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return withRefreshCookie(HttpStatus.CREATED, auth.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(HttpStatus.OK, auth.login(request));
    }

    // no body and no Authorization: the cookie is the credential. Any failure clears it.
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = RefreshTokenService.COOKIE, required = false) String token) {
        return auth.refresh(token)
                .<ResponseEntity<?>>map(signedIn -> withRefreshCookie(HttpStatus.OK, signedIn))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.SET_COOKIE, refreshTokens.clearedCookie().toString())
                        .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Please sign in again")));
    }

    // always 204: the token stops working if there is one, and the cookie is cleared either way
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshTokenService.COOKIE, required = false) String token) {
        auth.logout(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokens.clearedCookie().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(HttpStatus status, SignedIn signedIn) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshTokens.cookie(signedIn.refreshToken()).toString())
                .body(signedIn.body());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal UUID userId,
                              @Valid @RequestBody DeleteAccountRequest request) {
        auth.deleteAccount(userId, request);
    }
}
