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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.g4t1.users.TestTokens.serviceToken;

// The same server with no SMTP host and no bot token: an enabled channel can be saved, but sending says so
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"spring.mail.host=", "users.telegram-bot-token="})
@ActiveProfiles("test")
class SendNotificationNotConfiguredTest {

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
    void anEnabledChannelWithNoServerSettingsIsNotConfigured() {
        Account account = registered();
        String token = account.token();
        String id = account.id();
        put(token, "email", Map.of("enabled", true, "min_alert_severity", "low"));
        put(token, "telegram", Map.of("enabled", true, "chat_id", 42, "min_alert_severity", "low"));

        client.post().uri("/internal/users/" + id + "/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken(jwtSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("event", "alerts",
                        "paper", Map.of("id", UUID.randomUUID().toString()),
                        "report_id", 3,
                        "alerts", List.of(Map.of("id", 7, "change_type", "retraction", "alert_severity", "high",
                                "description", "Retracted."))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("not_configured")
                .jsonPath("$.channels.telegram").isEqualTo("not_configured");
    }

    @Test
    void theTestMessageToAnEnabledChannelWithNoServerSettingsIsNotConfigured() {
        String token = registered().token();
        put(token, "email", Map.of("enabled", true));
        put(token, "telegram", Map.of("enabled", true, "chat_id", 42));

        client.post().uri("/auth/me/notifications/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("not_configured")
                .jsonPath("$.channels.telegram").isEqualTo("not_configured");
    }

    private record Account(String token, String id) {
    }

    private Account registered() {
        String reply = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "researcher-" + UUID.randomUUID() + "@example.com", "password", "correct horse battery"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return new Account(JsonPath.read(reply, "$.token"), JsonPath.read(reply, "$.user.id"));
    }

    private void put(String token, String channel, Map<String, ?> body) {
        client.put().uri("/auth/me/notifications/" + channel)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk();
    }
}
