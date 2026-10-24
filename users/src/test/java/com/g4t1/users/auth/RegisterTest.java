package com.g4t1.users.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegisterTest {

    @LocalServerPort
    private int port;

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
