package com.g4t1.users.notification;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
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

// The same server with no APP_URL: the mail is sent without a link to the paper
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "users.app-url=")
@ActiveProfiles("test")
class SendNotificationNoLinkTest {

    private static final GreenMail mail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        mail.start();
    }

    @DynamicPropertySource
    static void pointAtMail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.port", () -> mail.getSmtp().getPort());
    }

    @AfterAll
    static void stopMail() {
        mail.stop();
    }

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    @Test
    void aMailWithNoAppUrlHasNoLinkToThePaper() throws Exception {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        String reply = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "researcher-" + UUID.randomUUID() + "@example.com", "password", "correct horse battery"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        String token = JsonPath.read(reply, "$.token");
        String id = JsonPath.read(reply, "$.user.id");
        client.put().uri("/auth/me/notifications/email")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", true, "min_alert_severity", "low"))
                .exchange()
                .expectStatus().isOk();

        client.post().uri("/internal/users/" + id + "/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken(jwtSecret))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("event", "alerts",
                        "paper", Map.of("id", "6b1f3c1e-2d34-4c1e-9d0a-0a2b4c6d8e10", "title", "Quantum Widgets"),
                        "report_id", 3,
                        "alerts", List.of(Map.of("id", 7, "change_type", "retraction", "alert_severity", "high",
                                "description", "Retracted."))))
                .exchange()
                .expectStatus().isOk();

        assertThat(mail.getReceivedMessages()).hasSize(1);
        assertThat(GreenMailUtil.getBody(mail.getReceivedMessages()[0])).doesNotContain("/papers/").doesNotContain("http");
    }
}
