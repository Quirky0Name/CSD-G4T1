package com.g4t1.users.auth;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LoginTest {

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void theRightEmailAndPasswordLogIn() {
        String email = newEmail();
        String registration = registered(email, "correct horse battery");

        String body = login(email, "correct horse battery")
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // login reads the account back from the database, and it should look exactly as register showed it
        assertThat(JsonPath.<Object>read(body, "$.user")).isEqualTo(JsonPath.read(registration, "$.user"));
        assertIsTokenFor(JsonPath.read(body, "$.token"), JsonPath.read(registration, "$.user.id"));
    }

    @Test
    void aWrongPasswordIsUnauthorized() {
        String email = newEmail();
        registered(email, "correct horse battery");

        login(email, "wrong horse battery")
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Wrong email or password");
    }

    // the same answer as a wrong password, so login never shows which emails have accounts
    @Test
    void anUnknownEmailIsUnauthorizedLikeAWrongPassword() {
        login(newEmail(), "correct horse battery")
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Wrong email or password");
    }

    // checked the way the contract describes it: HS256 (the Python services accept nothing else) with
    // the base64-decoded JWT_SECRET, sub the user's id, 24 hours to live, and no service role
    private void assertIsTokenFor(String token, String userId) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret)))
                .build()
                .parseSignedClaims(token);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(jws.getPayload().getSubject()).isEqualTo(userId);
        assertThat(jws.getPayload().get("role")).isNull();
        assertThat(jws.getPayload().getExpiration())
                .isCloseTo(Date.from(Instant.now().plus(Duration.ofHours(24))), 60_000);
    }

    private String registered(String email, String password) {
        return client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private RestTestClient.ResponseSpec login(String email, String password) {
        return client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange();
    }

    // a fresh address per test, so tests never collide in the shared database
    private static String newEmail() {
        return "researcher-" + UUID.randomUUID() + "@example.com";
    }
}
