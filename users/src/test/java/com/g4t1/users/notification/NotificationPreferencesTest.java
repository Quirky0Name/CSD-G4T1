package com.g4t1.users.notification;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void aBadChannelIsABadRequestNamingTheFieldAndChangesNothing(String name, String channel, Map<String, Object> body,
                                                                 String detail) {
        Account account = registered();
        put(account.token(), "email", Map.of("enabled", true, "min_alert_severity", "low")).expectStatus().isOk();
        put(account.token(), "telegram", Map.of("enabled", true, "chat_id", 42, "min_impact_level", "low"))
                .expectStatus().isOk();
        String before = get(account.token()).expectBody(String.class).returnResult().getResponseBody();

        put(account.token(), channel, body)
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").value(String.class, d -> assertThat(d).startsWith(detail));

        assertThat(get(account.token()).expectBody(String.class).returnResult().getResponseBody()).isEqualTo(before);
    }

    static Stream<Arguments> invalid() {
        Map<String, Object> noEnabled = Map.of("min_alert_severity", "high");
        return Stream.of(
                Arguments.of("email level in the wrong case", "email",
                        Map.of("enabled", true, "min_alert_severity", "High"), "min_alert_severity"),
                Arguments.of("email level none", "email",
                        Map.of("enabled", true, "min_change_severity", "none"), "min_change_severity"),
                Arguments.of("email level that is a number", "email",
                        Map.of("enabled", true, "min_impact_level", 3), "min_impact_level"),
                Arguments.of("email with no enabled", "email", noEnabled, "enabled"),
                Arguments.of("telegram level in the wrong case", "telegram",
                        Map.of("enabled", false, "min_impact_level", "MEDIUM"), "min_impact_level"),
                Arguments.of("telegram level none", "telegram",
                        Map.of("enabled", false, "min_alert_severity", "none"), "min_alert_severity"),
                Arguments.of("telegram with no enabled", "telegram", Map.of("chat_id", 7), "enabled"),
                Arguments.of("chat id 0", "telegram", Map.of("enabled", false, "chat_id", 0), "chat_id"),
                Arguments.of("chat id as a string", "telegram", Map.of("enabled", false, "chat_id", "12345"), "chat_id"),
                Arguments.of("chat id with a fraction", "telegram", Map.of("enabled", false, "chat_id", 1.5), "chat_id"),
                Arguments.of("telegram enabled with no chat id", "telegram", Map.of("enabled", true),
                        "chat_id is required to enable Telegram"));
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
        return put(token, "email", body);
    }

    private RestTestClient.ResponseSpec put(String token, String channel, Map<String, ?> body) {
        return client.put().uri("/auth/me/notifications/" + channel)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    private RestTestClient.ResponseSpec putTelegram(String token, Map<String, ?> body) {
        return put(token, "telegram", body);
    }
}
