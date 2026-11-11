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

import java.util.HashMap;
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

    @Test
    void putEmailReplacesThatChannelAndLeavesTelegramAlone() {
        Account account = registered();
        Map<String, Object> email = new HashMap<>();
        email.put("enabled", true);
        email.put("min_alert_severity", "low");
        email.put("min_change_severity", null);
        email.put("min_impact_level", "high");

        putEmail(account.token(), email)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email.enabled").isEqualTo(true)
                .jsonPath("$.email.address").isEqualTo(account.email())
                .jsonPath("$.email.min_alert_severity").isEqualTo("low")
                .jsonPath("$.email.min_change_severity").isEqualTo(null)
                .jsonPath("$.email.min_impact_level").isEqualTo("high")
                .jsonPath("$.telegram.enabled").isEqualTo(false)
                .jsonPath("$.telegram.min_alert_severity").isEqualTo("high");
        get(account.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email.enabled").isEqualTo(true)
                .jsonPath("$.email.min_alert_severity").isEqualTo("low")
                .jsonPath("$.email.min_change_severity").isEqualTo(null)
                .jsonPath("$.email.min_impact_level").isEqualTo("high")
                .jsonPath("$.telegram.enabled").isEqualTo(false)
                .jsonPath("$.telegram.min_alert_severity").isEqualTo("high")
                .jsonPath("$.telegram.min_change_severity").isEqualTo("medium")
                .jsonPath("$.telegram.min_impact_level").isEqualTo("medium");
    }

    // a PUT stores the whole channel: a threshold left out is null, not the old value or the default
    @Test
    void putEmailClearsAThresholdLeftOut() {
        Account account = registered();

        putEmail(account.token(), Map.of("enabled", false))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email.min_alert_severity").isEqualTo(null)
                .jsonPath("$.email.min_change_severity").isEqualTo(null)
                .jsonPath("$.email.min_impact_level").isEqualTo(null);
    }

    // a chat id can be past an int (and negative, for a group), so it round-trips as a long
    @Test
    void putTelegramReplacesThatChannelAndLeavesEmailAlone() {
        Account account = registered();
        Map<String, Object> telegram = new HashMap<>();
        telegram.put("enabled", true);
        telegram.put("chat_id", 5000000000L);
        telegram.put("min_alert_severity", null);
        telegram.put("min_change_severity", "low");
        telegram.put("min_impact_level", "high");

        putTelegram(account.token(), telegram)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.telegram.enabled").isEqualTo(true)
                .jsonPath("$.telegram.chat_id").isEqualTo(5000000000L)
                .jsonPath("$.telegram.min_alert_severity").isEqualTo(null)
                .jsonPath("$.telegram.min_change_severity").isEqualTo("low")
                .jsonPath("$.telegram.min_impact_level").isEqualTo("high")
                .jsonPath("$.email.enabled").isEqualTo(false)
                .jsonPath("$.email.min_alert_severity").isEqualTo("high");
        get(account.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.telegram.enabled").isEqualTo(true)
                .jsonPath("$.telegram.chat_id").isEqualTo(5000000000L)
                .jsonPath("$.telegram.min_alert_severity").isEqualTo(null)
                .jsonPath("$.telegram.min_change_severity").isEqualTo("low")
                .jsonPath("$.telegram.min_impact_level").isEqualTo("high")
                .jsonPath("$.email.enabled").isEqualTo(false)
                .jsonPath("$.email.min_change_severity").isEqualTo("medium")
                .jsonPath("$.email.min_impact_level").isEqualTo("medium");
    }

    @Test
    void putTelegramWithNoChatIdClearsTheOneSaved() {
        Account account = registered();
        putTelegram(account.token(), Map.of("enabled", true, "chat_id", -1001234567890L)).expectStatus().isOk();

        putTelegram(account.token(), Map.of("enabled", false))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.telegram.chat_id").isEqualTo(null);
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

    private RestTestClient.ResponseSpec putEmail(String token, Map<String, ?> body) {
        return client.put().uri("/auth/me/notifications/email")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private RestTestClient.ResponseSpec putTelegram(String token, Map<String, ?> body) {
        return client.put().uri("/auth/me/notifications/telegram")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }
}
