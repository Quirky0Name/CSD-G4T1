package com.g4t1.users.auth;

import com.g4t1.users.StorageStub;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.g4t1.users.TestTokens.assertIsServiceToken;
import static com.g4t1.users.TestTokens.badTokens;
import static com.g4t1.users.TestTokens.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;

// DELETE /auth/me: a researcher deleting their own account, with Storage Management clearing it out first
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeleteAccountTest {

    private static final String PASSWORD = "correct horse battery";

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

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        storage.reset();
    }

    @Test
    void deletingYourAccountClearsStorageThenRemovesIt() {
        Account account = registered();

        deleteAccount(account.token(), PASSWORD).expectStatus().isNoContent();

        assertThat(storage.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/internal/users/" + account.id());
            assertIsServiceToken(request.authorization(), jwtSecret);
        });
        login(account.email()).expectStatus().isUnauthorized();
    }

    @Test
    void deletingYourAccountDeletesYourProjects() {
        Account account = registered();
        client.post().uri("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Thesis"))
                .exchange()
                .expectStatus().isCreated();

        deleteAccount(account.token(), PASSWORD).expectStatus().isNoContent();

        // the token outlives the account by up to a day, and finds nothing left
        client.get().uri("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.projects").isEmpty();
    }

    // not a 401: the token is fine, and the frontend signs out on any 401
    @Test
    void aWrongPasswordIsForbiddenAndDeletesNothing() {
        Account account = registered();

        deleteAccount(account.token(), "not my password").expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Wrong password");

        assertThat(storage.requests()).isEmpty();
        login(account.email()).expectStatus().isOk();
    }

    @Test
    void aMissingOrBlankPasswordIsABadRequest() {
        Account account = registered();

        deleteAccount(account.token(), Map.of()).expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        deleteAccount(account.token(), "").expectStatus().isBadRequest();
        deleteAccount(account.token(), "   ").expectStatus().isBadRequest();

        assertThat(storage.requests()).isEmpty();
    }

    @Test
    void anAccountStorageCannotClearIsNotDeleted() {
        Account account = registered();
        storage.answerWith(500);

        deleteAccount(account.token(), PASSWORD).expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail")
                .isEqualTo("Storage Management couldn't clear your account, so it wasn't deleted");

        login(account.email()).expectStatus().isOk();
    }

    @Test
    void deletingAnAccountNeedsAUserToken() {
        client.method(HttpMethod.DELETE).uri("/auth/me")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("password", PASSWORD))
                .exchange().expectStatus().isUnauthorized();
        for (String token : badTokens(jwtSecret)) {
            deleteAccount(token, PASSWORD).expectStatus().isUnauthorized();
        }
        deleteAccount(serviceToken(jwtSecret), PASSWORD).expectStatus().isForbidden();

        assertThat(storage.requests()).isEmpty();
    }

    // a 401, so the frontend signs out whoever is still holding the token
    @Test
    void aDeletedAccountsTokenCantMakeProjects() {
        Account account = registered();
        deleteAccount(account.token(), PASSWORD).expectStatus().isNoContent();

        client.post().uri("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Thesis"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Your account no longer exists");
    }

    @Test
    void deletingAnAccountThatsAlreadyGoneIsUnauthorized() {
        Account account = registered();
        deleteAccount(account.token(), PASSWORD).expectStatus().isNoContent();

        deleteAccount(account.token(), PASSWORD).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.detail").isEqualTo("Your account no longer exists");
    }

    // a double-clicked delete: both reach Storage Management before either removes the account
    @Test
    void twoDeletesOfTheSameAccountAtOnceAreBothNoContent() throws InterruptedException {
        Account account = registered();
        storage.answerAfter(Duration.ofMillis(300));
        Callable<Integer> attempt = () -> deleteAccount(account.token(), PASSWORD)
                .expectBody(String.class).returnResult().getStatus().value();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            statuses = pool.invokeAll(List.of(attempt, attempt)).stream().map(Future::resultNow).toList();
        }

        assertThat(statuses).containsExactly(204, 204);
    }

        private record Account(String token, String id, String email) {
    }

    private Account registered() {
        String email = "researcher-" + UUID.randomUUID() + "@example.com";
        String body = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return new Account(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.user.id"), email);
    }

    private RestTestClient.ResponseSpec login(String email) {
        return client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange();
    }

    private RestTestClient.ResponseSpec deleteAccount(String token, String password) {
        return deleteAccount(token, Map.of("password", password));
    }

    private RestTestClient.ResponseSpec deleteAccount(String token, Map<String, String> body) {
        return client.method(HttpMethod.DELETE).uri("/auth/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }
}
