package com.g4t1.users.notification;

import com.g4t1.users.StorageStub;
import com.g4t1.users.TelegramStub;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.internet.MimeMessage;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.g4t1.users.TestTokens.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;

// POST /internal/users/{id}/notifications: Research Evaluation's events becoming mail, with GreenMail as the SMTP server
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SendNotificationTest {

    private static final StorageStub storage = new StorageStub();
    private static final TelegramStub telegram = new TelegramStub();
    private static final GreenMail mail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        mail.start();
    }

    @DynamicPropertySource
    static void pointAtTheStubs(DynamicPropertyRegistry registry) {
        registry.add("users.storage-url", storage::url);
        registry.add("users.telegram-api-url", telegram::url);
        registry.add("spring.mail.port", () -> mail.getSmtp().getPort());
    }

    @AfterAll
    static void stopServers() {
        storage.stop();
        telegram.stop();
        mail.stop();
    }

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    @Autowired
    private NotificationPreferencesRepository preferences;

    private RestTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        mail.purgeEmailFromAllMailboxes();
        telegram.reset();
    }

    @Test
    void anAlertsEventIsMailedWithOnlyTheAlertsAtOrAboveTheThreshold() throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", true, "min_alert_severity", "high"));

        postEvent(account.id(), alertsEvent(
                alert(7, "retraction", "high", "The journal retracted the paper."),
                alert(8, "citation_jump", "low", "Cited 12 more times.")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("sent")
                .jsonPath("$.channels.telegram").isEqualTo("disabled");

        MimeMessage[] received = mail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).extracting(Object::toString).containsExactly(account.email());
        assertThat(received[0].getSubject()).isEqualTo("Research Assistant: New alerts on \"Quantum Widgets\"");
        String body = GreenMailUtil.getBody(received[0]);
        assertThat(body).contains("HIGH retraction: The journal retracted the paper.");
        assertThat(body).doesNotContain("citation_jump");
    }

    @Test
    void eachChannelGetsTheAlertsAtOrAboveItsOwnThreshold() throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", true, "min_alert_severity", "high"));
        putTelegram(account, Map.of("enabled", true, "chat_id", 5000000000L, "min_alert_severity", "low"));

        postEvent(account.id(), alertsEvent(
                alert(8, "citation_jump", "low", "Cited 12 more times."),
                alert(7, "retraction", "high", "The journal retracted the paper.")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("sent")
                .jsonPath("$.channels.telegram").isEqualTo("sent");

        MimeMessage[] received = mail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(GreenMailUtil.getBody(received[0]))
                .contains("HIGH retraction: The journal retracted the paper.")
                .doesNotContain("citation_jump");
        assertThat(telegram.requests()).hasSize(1);
        TelegramStub.Request sent = telegram.requests().get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/bottest-bot-token/sendMessage");
        assertThat((Number) JsonPath.read(sent.body(), "$.chat_id")).isEqualTo(5000000000L);
        assertThat((Boolean) JsonPath.read(sent.body(), "$.disable_web_page_preview")).isTrue();
        assertThat(JsonPath.<Map<String, Object>>read(sent.body(), "$")).doesNotContainKey("parse_mode");
        assertThat(JsonPath.<String>read(sent.body(), "$.text"))
                .contains("HIGH retraction: The journal retracted the paper.", "LOW citation_jump: Cited 12 more times.")
                .satisfies(text -> assertThat(text.indexOf("HIGH retraction")).isLessThan(text.indexOf("LOW citation_jump")));
    }

    @Test
    void anEventBelowTheThresholdOrWithANullOneMatchesNothingAndSendsNothing() throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", true, "min_alert_severity", "high"));
        Map<String, Object> telegramChannel = new HashMap<>();
        telegramChannel.put("enabled", true);
        telegramChannel.put("chat_id", 42);
        telegramChannel.put("min_alert_severity", null);
        putTelegram(account, telegramChannel);

        postEvent(account.id(), alertsEvent(alert(8, "citation_jump", "medium", "Cited 12 more times.")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("no_match")
                .jsonPath("$.channels.telegram").isEqualTo("no_match");

        assertThat(mail.getReceivedMessages()).isEmpty();
        assertThat(telegram.requests()).isEmpty();
    }

    @ParameterizedTest(name = "change {0} and impact {1} gives {2}")
    @MethodSource("reportLevels")
    void aReportSendsWhenEitherItsChangeSeverityOrItsImpactLevelReachesTheThreshold(String change, String impact,
                                                                                     String outcome, int mails) throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", true, "min_change_severity", "high", "min_impact_level", "high"));

        postEvent(account.id(), reportEvent(change, "The abstract changed.", impact, null, null))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo(outcome);

        assertThat(mail.getReceivedMessages()).hasSize(mails);
    }

    static Stream<Arguments> reportLevels() {
        return Stream.of(
                Arguments.of("high", "low", "sent", 1),
                Arguments.of("medium", "high", "sent", 1),
                Arguments.of("medium", "medium", "no_match", 0),
                Arguments.of("none", null, "no_match", 0));
    }

    @Test
    void anAssessedReportIsMailedWithItsSummaryEvaluationAndRecommendation() throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", true, "min_change_severity", "low"));

        postEvent(account.id(), reportEvent("high", "The paper's main result was withdrawn.", "medium",
                "Your section 3 leans on that result.", "Cite the correction instead."))
                .expectStatus().isOk();

        MimeMessage[] received = mail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getSubject()).isEqualTo("Research Assistant: Report ready on \"Quantum Widgets\"");
        assertThat(GreenMailUtil.getBody(received[0])).contains(
                "Quantum Widgets", "DOI: 10.1234/qw",
                "Change: high", "The paper's main result was withdrawn.",
                "Impact on your draft: medium", "Your section 3 leans on that result.",
                "What to do:", "Cite the correction instead.");
    }

    @Test
    void telegramRefusingTheMessageIsFailedAndEmailIsStillSent() throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", true, "min_alert_severity", "low"));
        putTelegram(account, Map.of("enabled", true, "chat_id", 42, "min_alert_severity", "low"));
        telegram.answerWith(403);

        postEvent(account.id(), alertsEvent(alert(7, "retraction", "high", "The journal retracted the paper.")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("sent")
                .jsonPath("$.channels.telegram").isEqualTo("failed");

        assertThat(mail.getReceivedMessages()).hasSize(1);
        assertThat(telegram.requests()).hasSize(1);
    }

    @Test
    void aChannelTurnedOffIsDisabledAndSendsNothing() throws Exception {
        Account account = registered();
        putEmail(account, Map.of("enabled", false, "min_alert_severity", "low"));
        putTelegram(account, Map.of("enabled", false, "chat_id", 42, "min_alert_severity", "low"));

        postEvent(account.id(), alertsEvent(alert(7, "retraction", "high", "The journal retracted the paper.")))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.email").isEqualTo("disabled")
                .jsonPath("$.channels.telegram").isEqualTo("disabled");

        assertThat(mail.getReceivedMessages()).isEmpty();
        assertThat(telegram.requests()).isEmpty();
    }

    @Test
    void aTelegramMessageOverTheLimitIsCutToItAndEndsWithAnEllipsis() throws Exception {
        Account account = registered();
        putTelegram(account, Map.of("enabled", true, "chat_id", 42, "min_alert_severity", "low"));

        postEvent(account.id(), alertsEvent(alert(7, "retraction", "high", "x".repeat(5000))))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channels.telegram").isEqualTo("sent");

        String text = JsonPath.read(telegram.requests().get(0).body(), "$.text");
        assertThat(text).hasSize(4096).endsWith("x\u2026").startsWith("Quantum Widgets\nDOI: 10.1234/qw");
    }

    // an emoji is two UTF-16 units: a cut between them would leave half of one before the ellipsis
    @Test
    void aTelegramCutNeverSplitsACharacterInTwo() throws Exception {
        Account account = registered();
        putTelegram(account, Map.of("enabled", true, "chat_id", 42, "min_alert_severity", "low"));

        postEvent(account.id(), alertsEvent(alert(7, "retraction", "high", "\uD83D\uDE00".repeat(3000))))
                .expectStatus().isOk();

        String text = JsonPath.read(telegram.requests().get(0).body(), "$.text");
        assertThat(text).endsWith("\uD83D\uDE00\u2026").hasSizeLessThanOrEqualTo(4096);
    }

    @ParameterizedTest(name = "{0} is a 400")
    @MethodSource("badEvents")
    void anEventBreakingTheRulesIsABadRequestNamingTheField(String name, Map<String, Object> event, String detail) throws Exception {
        Account account = registered();

        postEvent(account.id(), event)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").isEqualTo(detail);
    }

    static Stream<Arguments> badEvents() {
        Map<String, Object> noEvent = new HashMap<>(alertsEvent(alert(7, "retraction", "high", "Retracted.")));
        noEvent.remove("event");
        Map<String, Object> noPaper = new HashMap<>(alertsEvent(alert(7, "retraction", "high", "Retracted.")));
        noPaper.remove("paper");
        Map<String, Object> noSummary = reportEvent("high", null, "low", null, null);
        return Stream.of(
                Arguments.of("an unknown event", Map.of("event", "digest", "paper", paper()),
                        "event must be alerts or report_assessed"),
                Arguments.of("no event", noEvent, "event must be alerts or report_assessed"),
                Arguments.of("no paper", noPaper, "paper is required"),
                Arguments.of("no alerts", alertsEvent(), "alerts must have at least one alert"),
                Arguments.of("an unknown alert severity", alertsEvent(alert(7, "retraction", "critical", "Retracted.")),
                        "alert_severity must be low, medium or high"),
                Arguments.of("an unknown change severity", reportEvent("severe", "Changed.", "low", null, null),
                        "change_severity must be none, low, medium or high"),
                Arguments.of("an unknown impact level", reportEvent("high", "Changed.", "none", null, null),
                        "impact_level must be low, medium, high or null"),
                Arguments.of("no change summary", noSummary, "change_summary is required"));
    }

    @Test
    void anUnknownUserIs404() throws Exception {
        UUID unknown = UUID.randomUUID();

        postEvent(unknown.toString(), alertsEvent(alert(7, "retraction", "high", "Retracted.")))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No user " + unknown);
    }

    // the dev mode admin has an account and no preferences row once it was created after the migration
    @Test
    void anAccountWithNoPreferencesRowIs404() throws Exception {
        Account account = registered();
        preferences.deleteById(UUID.fromString(account.id()));

        postEvent(account.id(), alertsEvent(alert(7, "retraction", "high", "Retracted.")))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No user " + account.id());
    }

    @Test
    void aUserTokenCannotPostAnEvent() throws Exception {
        Account account = registered();

        client.post().uri("/internal/users/" + account.id() + "/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(alertsEvent(alert(7, "retraction", "high", "Retracted.")))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(mail.getReceivedMessages()).isEmpty();
    }

    private static Map<String, Object> alert(int id, String changeType, String severity, String description) {
        return Map.of("id", id, "change_type", changeType, "alert_severity", severity, "description", description);
    }

    private static Map<String, Object> alertsEvent(Map<String, Object>... alerts) {
        return Map.of("event", "alerts", "paper", paper(), "report_id", 3, "alerts", List.of(alerts));
    }

    private static Map<String, Object> reportEvent(String changeSeverity, String changeSummary, String impactLevel,
                                                   String evaluation, String recommendation) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "report_assessed");
        event.put("paper", paper());
        event.put("report_id", 3);
        event.put("change_severity", changeSeverity);
        event.put("change_summary", changeSummary);
        event.put("impact_level", impactLevel);
        event.put("evaluation", evaluation);
        event.put("recommendation", recommendation);
        return event;
    }

    private static Map<String, Object> paper() {
        return Map.of("id", "6b1f3c1e-2d34-4c1e-9d0a-0a2b4c6d8e10", "title", "Quantum Widgets", "doi", "10.1234/qw");
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

    private void putEmail(Account account, Map<String, ?> body) {
        client.put().uri("/auth/me/notifications/email")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk();
    }

    private void putTelegram(Account account, Map<String, ?> body) {
        client.put().uri("/auth/me/notifications/telegram")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk();
    }

    private RestTestClient.ResponseSpec postEvent(String userId, Map<String, Object> event) {
        return client.post().uri("/internal/users/" + userId + "/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken(jwtSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .exchange();
    }
}
