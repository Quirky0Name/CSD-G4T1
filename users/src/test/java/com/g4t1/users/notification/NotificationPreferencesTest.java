package com.g4t1.users.notification;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;
import java.util.UUID;

// GET and PUT /auth/me/notifications: a researcher reading and replacing their own notification preferences
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationPreferencesTest {

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
    void aNewAccountStartsWithBothChannelsOffAndTheDefaultThresholds() {
        Account account = registered();

        get(account.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email.enabled").isEqualTo(false)
                .jsonPath("$.email.address").isEqualTo(account.email())
                .jsonPath("$.email.min_alert_severity").isEqualTo("high")
                .jsonPath("$.email.min_change_severity").isEqualTo("medium")
                .jsonPath("$.email.min_impact_level").isEqualTo("medium")
                .jsonPath("$.telegram.enabled").isEqualTo(false)
                .jsonPath("$.telegram.chat_id").isEqualTo(null)
                .jsonPath("$.telegram.min_alert_severity").isEqualTo("high")
                .jsonPath("$.telegram.min_change_severity").isEqualTo("medium")
                .jsonPath("$.telegram.min_impact_level").isEqualTo("medium");
    }

    private record Account(String token, String id, String email) {
    }

    private Account registered() {
        String email = "researcher-" + UUID.randomUUID() + "@example.com";
        String reply = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "correct horse battery"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return new Account(JsonPath.read(reply, "$.token"), JsonPath.read(reply, "$.user.id"), email);
    }

    private RestTestClient.ResponseSpec get(String token) {
        return client.get().uri("/auth/me/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }
}
