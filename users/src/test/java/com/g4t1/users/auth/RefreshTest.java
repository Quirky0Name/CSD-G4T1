package com.g4t1.users.auth;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.g4t1.users.auth.TokenAssertions.assertClearsRefreshCookie;
import static com.g4t1.users.auth.TokenAssertions.assertIsTokenFor;
import static com.g4t1.users.auth.TokenAssertions.assertSetsRefreshCookie;
import static org.assertj.core.api.Assertions.assertThat;

// POST /auth/refresh and POST /auth/logout: the refresh token in its cookie gets a new access token,
// works once, and is cleared on sign out (docs/CONTRACTS.md, "Auth")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefreshTest {

    private static final String PASSWORD = "correct horse battery";

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aValidRefreshTokenGivesANewAccessTokenAndANewRefreshToken() {
        Account account = registered();

        EntityExchangeResult<String> result = refresh(account.refreshToken())
                .expectStatus().isOk()
                .expectBody(String.class).returnResult();

        String body = result.getResponseBody();
        assertIsTokenFor(JsonPath.read(body, "$.token"), account.id(), jwtSecret);
        assertThat(JsonPath.<String>read(body, "$.user.id")).isEqualTo(account.id());
        assertThat(JsonPath.<String>read(body, "$.user.role")).isEqualTo("user");
        String next = assertSetsRefreshCookie(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE), false);
        assertThat(next).isNotEqualTo(account.refreshToken());
        // the new one works too: the week slides with every refresh
        refresh(next).expectStatus().isOk();
    }

    @Test
    void aRefreshTokenWorksOnce() {
        Account account = registered();
        refresh(account.refreshToken()).expectStatus().isOk();

        expectSignInAgain(refresh(account.refreshToken()));
    }

    @Test
    void noCookieIsSignInAgain() {
        expectSignInAgain(client.post().uri("/auth/refresh").exchange());
    }

    @Test
    void anUnknownTokenIsSignInAgain() {
        expectSignInAgain(refresh("not-a-token-this-server-ever-issued-0000000"));
    }

    @Test
    void anExpiredTokenIsSignInAgain() {
        Account account = registered();
        jdbc.update("update users.refresh_tokens set expires_at = ? where user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), UUID.fromString(account.id()));

        expectSignInAgain(refresh(account.refreshToken()));
    }

    @Test
    void aDeletedAccountsTokenIsSignInAgain() {
        Account account = registered();
        jdbc.update("delete from users.users where id = ?", UUID.fromString(account.id()));

        expectSignInAgain(refresh(account.refreshToken()));
    }

    @Test
    void theAdminsRefreshGivesAnAdminToken() {
        // the admin account as AdminAccount makes it in dev mode; its address can't be registered
        users.deleteByEmail(AdminAccount.EMAIL);
        users.saveAndFlush(new User(AdminAccount.EMAIL, new BCryptPasswordEncoder().encode(PASSWORD)));
        try {
            EntityExchangeResult<String> login = login(AdminAccount.EMAIL)
                    .expectStatus().isOk()
                    .expectBody(String.class).returnResult();
            String cookie = assertSetsRefreshCookie(login.getResponseHeaders().get(HttpHeaders.SET_COOKIE), false);

            String body = refresh(cookie).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

            assertThat(Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).build()
                    .parseSignedClaims(JsonPath.read(body, "$.token")).getPayload().get("role")).isEqualTo("admin");
            assertThat(JsonPath.<String>read(body, "$.user.role")).isEqualTo("admin");
        } finally {
            users.deleteByEmail(AdminAccount.EMAIL);
        }
    }

    // two tabs refreshing with the same token at once: the conditional update lets exactly one through
    @Test
    void twoRefreshesAtOnceWithOneTokenGiveOneSuccess() throws InterruptedException {
        Account account = registered();
        Callable<Integer> attempt = () -> refresh(account.refreshToken())
                .expectBody(String.class).returnResult().getStatus().value();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            statuses = pool.invokeAll(List.of(attempt, attempt)).stream().map(Future::resultNow).toList();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
    }

    @Test
    void afterLogoutTheTokenIsSignInAgainAndTheCookieIsCleared() {
        Account account = registered();

        EntityExchangeResult<Void> logout = client.post().uri("/auth/logout")
                .cookie("refresh_token", account.refreshToken())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
        assertClearsRefreshCookie(logout.getResponseHeaders().get(HttpHeaders.SET_COOKIE));

        expectSignInAgain(refresh(account.refreshToken()));
    }

    @Test
    void logoutWithNoCookieOrAnUnknownOneIsStillNoContent() {
        EntityExchangeResult<Void> none = client.post().uri("/auth/logout").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
        assertClearsRefreshCookie(none.getResponseHeaders().get(HttpHeaders.SET_COOKIE));

        client.post().uri("/auth/logout").cookie("refresh_token", "unknown").exchange()
                .expectStatus().isNoContent();
    }

    // the only way to see what's stored: no reply ever shows the table
    @Test
    void onlyTheTokensHashIsStored() {
        Account account = registered();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from users.refresh_tokens where user_id = ?", UUID.fromString(account.id()));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.values()).noneMatch(value -> value != null && value.toString().contains(account.refreshToken()));
            assertThat((String) row.get("token_hash")).matches("[0-9a-f]{64}");
            assertThat(row.get("used_at")).isNull();
        });
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "users.refresh-cookie-secure=true")
    class OnTheDeployedServer {

        @LocalServerPort
        private int securePort;

        @Test
        void theCookieIsSecure() {
            RestTestClient secureClient = RestTestClient.bindToServer().baseUrl("http://localhost:" + securePort).build();
            EntityExchangeResult<String> result = secureClient.post().uri("/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", newEmail(), "password", PASSWORD))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody(String.class).returnResult();

            assertSetsRefreshCookie(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE), true);
        }
    }

    private void expectSignInAgain(RestTestClient.ResponseSpec response) {
        EntityExchangeResult<String> result = response
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(String.class).returnResult();
        assertThat(JsonPath.<String>read(result.getResponseBody(), "$.detail")).isEqualTo("Please sign in again");
        assertClearsRefreshCookie(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE));
    }

    private record Account(String id, String refreshToken) {
    }

    private Account registered() {
        EntityExchangeResult<String> result = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", newEmail(), "password", PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult();
        String cookie = assertSetsRefreshCookie(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE), false);
        return new Account(JsonPath.read(result.getResponseBody(), "$.user.id"), cookie);
    }

    private RestTestClient.ResponseSpec login(String email) {
        return client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange();
    }

    private RestTestClient.ResponseSpec refresh(String token) {
        return client.post().uri("/auth/refresh").cookie("refresh_token", token).exchange();
    }

    private static String newEmail() {
        return "researcher-" + UUID.randomUUID() + "@example.com";
    }
}
