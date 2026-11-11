package com.g4t1.users.notification;

import com.g4t1.users.StorageStub;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.g4t1.users.TestTokens.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;

// POST /internal/users/{id}/notifications: Research Evaluation's events becoming mail, with GreenMail as the SMTP server
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SendNotificationTest {

    private static final StorageStub storage = new StorageStub();
    private static final GreenMail mail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        mail.start();
    }

    @DynamicPropertySource
    static void pointAtTheStubs(DynamicPropertyRegistry registry) {
        registry.add("users.storage-url", storage::url);
        registry.add("spring.mail.port", () -> mail.getSmtp().getPort());
    }

    @AfterAll
    static void stopServers() {
        storage.stop();
        mail.stop();
    }

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    private RestTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        mail.purgeEmailFromAllMailboxes();
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

    private static Map<String, Object> alert(int id, String changeType, String severity, String description) {
        return Map.of("id", id, "change_type", changeType, "alert_severity", severity, "description", description);
    }

    private static Map<String, Object> alertsEvent(Map<String, Object>... alerts) {
        return Map.of("event", "alerts", "paper", paper(), "report_id", 3, "alerts", List.of(alerts));
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

    private RestTestClient.ResponseSpec postEvent(String userId, Map<String, Object> event) {
        return client.post().uri("/internal/users/" + userId + "/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken(jwtSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .exchange();
    }
}
