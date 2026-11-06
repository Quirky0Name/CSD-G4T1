package com.g4t1.users.auth;

import com.g4t1.users.UsersApplication;
import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The account is made or removed as the app starts, so each test starts the real app itself, with dev
// mode on or off, against the same in-memory database.
class AdminAccountTest {

    private static final String PASSWORD = "12345679";

    @Test
    void withDevModeOnTheAdminLogsInAndGetsAnAdminToken() {
        try (var app = start(true)) {
            String body = loginBody(app);

            assertThat(JsonPath.<String>read(body, "$.user.email")).isEqualTo("admin");
            assertThat(JsonPath.<String>read(body, "$.user.role")).isEqualTo("admin");
            Claims claims = claims(app, JsonPath.read(body, "$.token"));
            assertThat(claims.get("role")).isEqualTo("admin");
            assertThat(claims.getSubject()).isEqualTo(JsonPath.<String>read(body, "$.user.id"));
        }
    }

    // reads the database directly, as RegisterTest does: no reply ever shows the stored password
    @Test
    void startingTwiceLeavesOneRowHoldingOnlyTheBcryptHash() {
        start(true).close();
        try (var app = start(true)) {
            var rows = app.getBean(JdbcTemplate.class)
                    .queryForList("select password_hash from users.users where email = 'admin'", String.class);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).isNotEqualTo(PASSWORD);
            assertThat(new BCryptPasswordEncoder().matches(PASSWORD, rows.getFirst())).isTrue();
        }
    }

    @Test
    void aWrongPasswordIsUnauthorized() {
        try (var app = start(true)) {
            login(app, "admin", "123456789")
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.detail").isEqualTo("Wrong email or password");
        }
    }

    @Test
    void withDevModeOffARowLeftFromAnEarlierRunIsDeleted() {
        start(true).close();
        try (var app = start(false)) {
            Integer rows = app.getBean(JdbcTemplate.class)
                    .queryForObject("select count(*) from users.users where email = 'admin'", Integer.class);

            assertThat(rows).isZero();
            login(app, "admin", PASSWORD)
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.detail").isEqualTo("Wrong email or password");
        }
    }

    @Test
    void theAdminTokenIsNotAResearcher() {
        try (var app = start(true)) {
            String token = JsonPath.read(loginBody(app), "$.token");

            client(app).get().uri("/projects")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange()
                    .expectStatus().isForbidden();
            client(app).method(HttpMethod.DELETE).uri("/auth/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("password", PASSWORD))
                    .exchange()
                    .expectStatus().isForbidden();
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

    private static RestTestClient.ResponseSpec login(ConfigurableApplicationContext app, String email, String password) {
        return client(app).post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange();
    }

    private static String loginBody(ConfigurableApplicationContext app) {
        return login(app, "admin", PASSWORD)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private static Claims claims(ConfigurableApplicationContext app, String token) {
        String secret = app.getEnvironment().getProperty("users.jwt-secret");
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))).build()
                .parseSignedClaims(token).getPayload();
    }
}
