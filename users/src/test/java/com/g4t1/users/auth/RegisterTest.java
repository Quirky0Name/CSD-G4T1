package com.g4t1.users.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
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
