package com.g4t1.users.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Arrays;
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
        TokenAssertions.assertIsTokenFor(
                JsonPath.read(body, "$.token"), JsonPath.read(registration, "$.user.id"), jwtSecret);
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

    @Test
    void anEmailLogsInWhateverItsLetterCase() {
        String email = newEmail();
        String id = JsonPath.read(registered(email, "correct horse battery"), "$.user.id");

        login(email.toUpperCase(), "correct horse battery")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.user.id").isEqualTo(id);
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

    // An unknown email answered at once would give away which emails have accounts, since a real account's
    // wrong password waits for BCrypt. Generous on purpose: without the fix the gap is about 10 times.
    @Test
    void anUnknownEmailTakesAboutAsLongAsAWrongPassword() {
        String email = newEmail();
        registered(email, "correct horse battery");
        login(email, "wrong horse battery").expectStatus().isUnauthorized();
        login(newEmail(), "wrong horse battery").expectStatus().isUnauthorized();

        long wrongPassword = medianNanos(() -> login(email, "wrong horse battery").expectStatus().isUnauthorized());
        long unknownEmail = medianNanos(() -> login(newEmail(), "wrong horse battery").expectStatus().isUnauthorized());

        assertThat(unknownEmail).isGreaterThanOrEqualTo(wrongPassword / 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\": \"correct horse battery\"}",
            "{\"email\": \"someone@example.com\"}",
            "{\"email\": \"\", \"password\": \"correct horse battery\"}",
            "{\"email\": \"someone@example.com\", \"password\": \"        \"}"})
    void aMissingOrBlankEmailOrPasswordIsABadRequest(String body) {
        client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
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

    private static long medianNanos(Runnable attempt) {
        long[] times = new long[5];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            attempt.run();
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        return times[times.length / 2];
    }

    // a fresh address per test, so tests never collide in the shared database
    private static String newEmail() {
        return "researcher-" + UUID.randomUUID() + "@example.com";
    }
}
