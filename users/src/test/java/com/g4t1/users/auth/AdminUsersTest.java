package com.g4t1.users.auth;

import com.g4t1.users.TestTokens;
import com.g4t1.users.UsersApplication;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// GET /auth/admin/users. Each test starts the real app, because a start with dev mode off deletes the admin.
class AdminUsersTest {

    @Test
    void theAdminSeesTheAccountsNewestFirstWithoutItself() {
        try (var app = start(true)) {
            String older = newEmail();
            String newer = newEmail();
            registered(app, older);
            registered(app, newer);

            String body = client(app).get().uri("/auth/admin/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken(app))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();

            List<String> emails = JsonPath.read(body, "$.users[*].email");
            assertThat(emails).doesNotContain("admin");
            assertThat(emails.indexOf(newer)).isNotNegative().isLessThan(emails.indexOf(older));
            assertThat(JsonPath.<List<String>>read(body, "$.users[*].role")).containsOnly("user");
            assertThat(body).doesNotContain("password");
        }
    }

    @Test
    void anythingButAnAdminTokenIsRefused() {
        try (var app = start(true)) {
            client(app).get().uri("/auth/admin/users")
                    .exchange()
                    .expectStatus().isUnauthorized();
            client(app).get().uri("/auth/admin/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                    .exchange()
                    .expectStatus().isUnauthorized();
            client(app).get().uri("/auth/admin/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(registered(app, newEmail()), "$.token"))
                    .exchange()
                    .expectStatus().isForbidden();
            client(app).get().uri("/auth/admin/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.serviceToken(secret(app)))
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    // a token from before the restart still checks out for up to an hour, but the endpoint is gone
    @Test
    void withDevModeOffAnAdminTokenGetsNotFound() {
        try (var app = start(false)) {
            client(app).get().uri("/auth/admin/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.adminToken(secret(app)))
                    .exchange()
                    .expectStatus().isNotFound();
        }
    }

    // command line arguments, because they beat application.yml's own users.dev-mode
    private static ConfigurableApplicationContext start(boolean devMode) {
        return new SpringApplicationBuilder(UsersApplication.class)
                .profiles("test")
                .run("--server.port=0", "--users.dev-mode=" + devMode);
    }

    private static RestTestClient client(ConfigurableApplicationContext app) {
        String port = app.getEnvironment().getProperty("local.server.port");
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String secret(ConfigurableApplicationContext app) {
        return app.getEnvironment().getProperty("users.jwt-secret");
    }

    private static String adminToken(ConfigurableApplicationContext app) {
        String body = client(app).post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "admin", "password", "12345679"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.token");
    }

    private static String registered(ConfigurableApplicationContext app, String email) {
        return client(app).post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", "correct horse battery"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static String newEmail() {
        return "researcher-" + UUID.randomUUID() + "@example.com";
    }
}
