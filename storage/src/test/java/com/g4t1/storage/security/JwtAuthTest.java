package com.g4t1.storage.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The "bad token" half of every endpoint's 401. Each endpoint's own tests already cover
// a missing token, a garbage one and the wrong role.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAuthTest {

    private static final SecretKey KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode("c3RvcmFnZS10ZXN0LXNlY3JldC1ub3QtZm9yLXByb2Qh"));
    private static final SecretKey OTHER_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode("c29tZS1vdGhlci10ZWFtcy1zZWNyZXQta2V5LTEyMzQ="));

    @Autowired
    MockMvc mvc;

    @Test
    void anExpiredUserTokenIsUnauthorized() throws Exception {
        String token = Jwts.builder().subject(UUID.randomUUID().toString())
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(KEY).compact();

        mvc.perform(get("/research-paper").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredServiceTokenIsUnauthorized() throws Exception {
        // Updating mints short-lived service tokens, so this is the one that actually runs out
        String token = Jwts.builder().subject("svc:updating").claim("role", "service")
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(KEY).compact();

        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsUnauthorized() throws Exception {
        String user = Jwts.builder().subject(UUID.randomUUID().toString()).signWith(OTHER_KEY).compact();
        String service = Jwts.builder().subject("svc:updating").claim("role", "service").signWith(OTHER_KEY).compact();

        mvc.perform(get("/research-paper").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, "Bearer " + service))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aUserTokenWhoseSubjectIsNotAUuidIsUnauthorized() throws Exception {
        String token = Jwts.builder().subject("alice@example.com").signWith(KEY).compact();

        mvc.perform(get("/research-paper").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithNoSubjectIsUnauthorized() throws Exception {
        // this used to escape the filter as a 500
        String user = Jwts.builder().claim("scope", "none").signWith(KEY).compact();
        String service = Jwts.builder().claim("role", "service").signWith(KEY).compact();

        mvc.perform(get("/research-paper").header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, "Bearer " + service))
                .andExpect(status().isUnauthorized());
    }
}
