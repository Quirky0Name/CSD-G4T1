package com.g4t1.users.notification;

import com.jayway.jsonpath.JsonPath;
import com.g4t1.users.StorageStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static com.g4t1.users.TestTokens.adminToken;
import static com.g4t1.users.TestTokens.badTokens;
import static com.g4t1.users.TestTokens.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;

// GET and PUT /auth/me/notifications: a researcher reading and replacing their own notification preferences
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationPreferencesTest {

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

    @Autowired
    private NotificationPreferencesRepository preferences;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

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

    @Test
    void aMissingOrBadTokenIsUnauthorizedOnEveryEndpoint() {
        for (String channel : List.of("email", "telegram")) {
            client.put().uri("/auth/me/notifications/" + channel).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("enabled", false)).exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/auth/me/notifications").exchange().expectStatus().isUnauthorized();
        for (String token : badTokens(jwtSecret)) {
            get(token).expectStatus().isUnauthorized();
            putEmail(token, Map.of("enabled", false)).expectStatus().isUnauthorized();
            putTelegram(token, Map.of("enabled", false)).expectStatus().isUnauthorized();
        }
    }

    @Test
    void anAdminOrServiceTokenIsForbiddenOnEveryEndpoint() {
        for (String token : List.of(adminToken(jwtSecret), serviceToken(jwtSecret))) {
            get(token).expectStatus().isForbidden();
            putEmail(token, Map.of("enabled", false)).expectStatus().isForbidden();
            putTelegram(token, Map.of("enabled", false)).expectStatus().isForbidden();
        }
    }

    // the token outlives the account by up to its lifetime; a 401 makes the frontend sign it out
    @Test
    void aTokenWhoseAccountWasDeletedIsUnauthorizedOnEveryEndpoint() {
        Account account = registered();
        jdbc.update("delete from users.users where id = ?", UUID.fromString(account.id()));

        get(account.token())
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Your account no longer exists");
        putEmail(account.token(), Map.of("enabled", false))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Your account no longer exists");
        putTelegram(account.token(), Map.of("enabled", false))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Your account no longer exists");
    }

    // read through the repository: no reply can show the row is gone
    @Test
    void deletingYourAccountDeletesYourPreferences() {
        Account account = registered();
        UUID id = UUID.fromString(account.id());
        assertThat(preferences.existsById(id)).isTrue();

        client.method(HttpMethod.DELETE).uri("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("password", "correct horse battery"))
                .exchange()
                .expectStatus().isNoContent();

        assertThat(preferences.existsById(id)).isFalse();
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

    // the second commit must not write back the other channel's old values, whichever order they land in
    @Test
    void aChannelSavedWhileTheOtherIsBeingSavedKeepsTheOthersNewValues() {
        Account account = registered();
        UUID id = UUID.fromString(account.id());
        TransactionTemplate outer = new TransactionTemplate(transactions);
        TransactionTemplate inner = new TransactionTemplate(transactions);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            NotificationPreferences stale = preferences.findById(id).orElseThrow();
            inner.executeWithoutResult(other ->
                    preferences.findById(id).orElseThrow().replaceEmail(true, "low", "low", "low"));
            stale.replaceTelegram(true, 42L, "medium", "medium", "medium");
        });

        NotificationPreferences saved = preferences.findById(id).orElseThrow();
        assertThat(saved.isEmailEnabled()).isTrue();
        assertThat(saved.getEmailMinAlertSeverity()).isEqualTo("low");
        assertThat(saved.isTelegramEnabled()).isTrue();
        assertThat(saved.getTelegramChatId()).isEqualTo(42L);
    }

    @Test
    void twoPutsAtOnceOnDifferentChannelsBothStick() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 50; round++) {
                Account account = registered();
                CountDownLatch go = new CountDownLatch(1);
                Future<?> email = pool.submit(() -> {
                    go.await();
                    return putEmail(account.token(), Map.of("enabled", true, "min_alert_severity", "low"))
                            .expectStatus().isOk();
                });
                Future<?> telegram = pool.submit(() -> {
                    go.await();
                    return putTelegram(account.token(), Map.of("enabled", true, "chat_id", 42, "min_alert_severity", "low"))
                            .expectStatus().isOk();
                });
                go.countDown();
                email.get();
                telegram.get();

                get(account.token())
                        .expectStatus().isOk()
                        .expectBody()
                        .jsonPath("$.email.enabled").isEqualTo(true)
                        .jsonPath("$.email.min_alert_severity").isEqualTo("low")
                        .jsonPath("$.telegram.enabled").isEqualTo(true)
                        .jsonPath("$.telegram.chat_id").isEqualTo(42);
            }
        } finally {
            pool.shutdownNow();
        }
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
