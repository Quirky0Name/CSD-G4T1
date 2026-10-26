package com.g4t1.users.auth;

import com.g4t1.users.project.StorageStub;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DELETE /auth/me: a researcher deleting their own account, with Storage Management clearing it out first
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeleteAccountTest {

    private static final String PASSWORD = "correct horse battery";

    private static final StorageStub storage = new StorageStub();

    @DynamicPropertySource
    static void pointAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("users.storage-url", storage::url);
    }

    @AfterAll
    static void stopStorage() {
        storage.stop();
    }

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        storage.reset();
    }

    @Test
    void deletingYourAccountClearsStorageThenRemovesIt() {
        Account account = registered();

        deleteAccount(account.token(), PASSWORD).expectStatus().isNoContent();

        assertThat(storage.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/internal/users/" + account.id());
            assertIsServiceToken(request.authorization());
        });
        login(account.email()).expectStatus().isUnauthorized();
    }

    private record Account(String token, String id, String email) {
    }

    private Account registered() {
        String email = "researcher-" + UUID.randomUUID() + "@example.com";
        String body = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return new Account(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.user.id"), email);
    }

    private RestTestClient.ResponseSpec login(String email) {
        return client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange();
    }

    private RestTestClient.ResponseSpec deleteAccount(String token, String password) {
        return client.method(HttpMethod.DELETE).uri("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("password", password))
                .exchange();
    }

    private void assertIsServiceToken(String authorization) {
        assertThat(authorization).startsWith("Bearer ");
        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).build()
                .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
        assertThat(claims.get("role")).isEqualTo("service");
        assertThat(claims.getSubject()).isEqualTo("svc:users");
        assertThat(claims.getExpiration()).isBefore(Date.from(Instant.now().plus(Duration.ofMinutes(10))));
    }
}
