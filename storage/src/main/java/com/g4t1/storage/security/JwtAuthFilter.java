package com.g4t1.storage.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Validates the JWT that User Management issues (or that Updating mints for itself)
 * without calling back to User Management. A missing or bad token just leaves the
 * request unauthenticated, and SecurityConfig answers it with a 401.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final SecretKey key;

    public JwtAuthFilter(String base64Secret) {
        // every service base64-decodes JWT_SECRET the same way, see docs/CONTRACTS.md
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(header.substring(BEARER.length()))
                        .getPayload();
                authenticate(claims);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticate(Claims claims) {
        Object principal;
        String role;
        if ("service".equals(claims.get("role", String.class))) {
            // e.g. sub=svc:updating; never owner-scoped to a real user
            principal = claims.getSubject();
            role = "ROLE_SERVICE";
        } else {
            // throws IllegalArgumentException for a non-UUID sub, which rejects the token
            principal = UUID.fromString(claims.getSubject());
            role = "ROLE_USER";
        }
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
