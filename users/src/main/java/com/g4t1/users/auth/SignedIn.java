package com.g4t1.users.auth;

// what signing in gives: the reply's body (the access token and the user) and the refresh token,
// which the controller sends only as a cookie
public record SignedIn(AuthResponse body, String refreshToken) {
}
