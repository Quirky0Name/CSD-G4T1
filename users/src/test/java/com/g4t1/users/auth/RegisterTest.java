package com.g4t1.users.auth;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegisterTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void registeringCreatesAnAccount() {
        String email = newEmail();

        register(email, "correct horse battery")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.user.id").value(String.class,
                        id -> assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException())
                .jsonPath("$.user.email").isEqualTo(email)
                .jsonPath("$.user.created_at").isNotEmpty()
                .jsonPath("$..password").doesNotExist();
    }

    @Test
    void anEmailThatAlreadyHasAnAccountIsAConflict() {
        String email = newEmail();
        register(email, "correct horse battery").expectStatus().isCreated();

        register(email, "a different password")
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("An account with this email already exists");
    }

    @Test
    void anEmailIsOneAccountWhateverItsLetterCase() {
        String email = newEmail();

        register(email.toUpperCase(), "correct horse battery")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.user.email").isEqualTo(email);

        register(email, "a different password").expectStatus().isEqualTo(409);
    }

    @Test
    void anEmailThatIsNotAnAddressIsABadRequest() {
        register("not-an-email", "correct horse battery")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aPasswordUnderEightCharactersIsABadRequest() {
        register(newEmail(), "seven77")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // BCrypt can't hash past 72 bytes; "é" is 2 bytes, so this is 37 characters but 74 bytes
    @Test
    void aPasswordOverSeventyTwoBytesIsABadRequest() {
        register(newEmail(), "é".repeat(37))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\": \"correct horse battery\"}",
            "{\"email\": \"someone@example.com\"}",
            "{\"email\": \"\", \"password\": \"correct horse battery\"}",
            "{\"email\": \"someone@example.com\", \"password\": \"        \"}"})
    void aMissingOrBlankEmailOrPasswordIsABadRequest(String body) {
        client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // The one test that reads the database directly: no reply ever contains the stored password,
    // so this is the only way to see it's kept as a BCrypt hash (an agreed exception to HTTP-only tests).
    @Test
    void aPasswordIsStoredOnlyAsItsBcryptHash() {
        String email = newEmail();
        register(email, "correct horse battery").expectStatus().isCreated();

        String stored = jdbc.queryForObject(
                "select password_hash from users.users where email = ?", String.class, email);

        assertThat(stored).isNotEqualTo("correct horse battery");
        assertThat(new BCryptPasswordEncoder().matches("correct horse battery", stored)).isTrue();
    }

    @Test
    void registeringLogsTheResearcherIn() {
        String body = register(newEmail(), "correct horse battery")
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();

        assertIsTokenFor(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.user.id"));
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

    private RestTestClient.ResponseSpec register(String email, String password) {
        return client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange();
    }

    // a fresh address per test, so tests never collide in the shared database
    private static String newEmail() {
        return "researcher-" + UUID.randomUUID() + "@example.com";
    }
}
