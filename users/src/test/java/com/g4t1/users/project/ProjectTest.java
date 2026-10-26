package com.g4t1.users.project;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProjectTest {

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
    void creatingAProjectReturnsIt() {
        String token = registeredToken();

        create(token, "Thesis")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").value(String.class,
                        id -> assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException())
                .jsonPath("$.name").isEqualTo("Thesis")
                .jsonPath("$.created_at").isNotEmpty();
    }

    // the list reads the project back from the database, which keeps microseconds
    @Test
    void aNewProjectLooksTheSameWhenListed() {
        String token = registeredToken();
        String created = create(token, "Thesis").expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();

        Object listed = JsonPath.read(listBody(token), "$.projects[0]");

        assertThat(listed).isEqualTo(JsonPath.read(created, "$"));
    }

    @Test
    void theListShowsYourProjectsNewestFirst() throws InterruptedException {
        String token = registeredToken();
        create(token, "First").expectStatus().isCreated();
        // created_at is set as each request arrives; keep the two apart so the order is certain
        Thread.sleep(5);
        create(token, "Second").expectStatus().isCreated();

        List<String> names = JsonPath.read(listBody(token), "$.projects[*].name");

        assertThat(names).containsExactly("Second", "First");
    }

    @Test
    void theListShowsOnlyYourProjects() {
        String mine = registeredToken();
        String theirs = registeredToken();
        create(mine, "Mine").expectStatus().isCreated();
        create(theirs, "Theirs").expectStatus().isCreated();

        List<String> names = JsonPath.read(listBody(mine), "$.projects[*].name");

        assertThat(names).containsExactly("Mine");
    }

    @Test
    void aBlankOrOverLongNameIsABadRequest() {
        String token = registeredToken();

        create(token, "").expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        create(token, "   ").expectStatus().isBadRequest();
        create(token, "x".repeat(101)).expectStatus().isBadRequest();
        create(token, "x".repeat(100)).expectStatus().isCreated();
    }

    @Test
    void aNameYouAlreadyUseIsAConflictEvenWithSpacesAroundIt() {
        String mine = registeredToken();
        create(mine, "Thesis").expectStatus().isCreated();

        create(mine, " Thesis  ").expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("You already have a project called Thesis");
        // the spaces aren't kept; apart from them names are exact, and each researcher has their own
        create(mine, " Draft ").expectStatus().isCreated()
                .expectBody().jsonPath("$.name").isEqualTo("Draft");
        create(mine, "thesis").expectStatus().isCreated();
        create(registeredToken(), "Thesis").expectStatus().isCreated();
    }

    // a double-clicked create, or two renames at once: the database has the final say, as it does for emails
    @Test
    void aNameCantEndUpTwiceEvenWhenSentAtOnce() throws InterruptedException {
        String token = registeredToken();
        String draft = createdId(token, "Draft");
        String notes = createdId(token, "Notes");

        List<Integer> creates = atOnce(Collections.nCopies(4, () -> status(create(token, "Thesis"))));
        List<Integer> renames = atOnce(List.of(
                () -> status(rename(token, draft, "Final")), () -> status(rename(token, notes, "Final"))));

        assertThat(creates).containsExactlyInAnyOrder(201, 409, 409, 409);
        assertThat(renames).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void renamingChangesTheNameUnlessYouAlreadyUseIt() {
        String token = registeredToken();
        String id = createdId(token, "Draft");
        create(token, "Thesis").expectStatus().isCreated();

        rename(token, id, " Final draft ").expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(id)
                .jsonPath("$.name").isEqualTo("Final draft");
        rename(token, id, "Thesis").expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.detail").isEqualTo("You already have a project called Thesis");
        rename(token, id, "   ").expectStatus().isBadRequest();
        rename(token, id, "x".repeat(101)).expectStatus().isBadRequest();

        List<String> names = JsonPath.read(listBody(token), "$.projects[*].name");
        assertThat(names).containsExactlyInAnyOrder("Final draft", "Thesis");
    }

    // what a rename box sends back when it's saved without a change
    @Test
    void renamingAProjectToItsOwnNameIsFine() {
        String token = registeredToken();
        String id = createdId(token, "Thesis");

        rename(token, id, " Thesis ").expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Thesis");
    }

    @Test
    void deletingAProjectClearsItInStorageThenRemovesIt() {
        Account account = registered();
        String projectId = createdId(account.token(), "Thesis");

        delete(account.token(), projectId).expectStatus().isNoContent();

        assertThat(storage.requests()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("DELETE");
            assertThat(request.path()).isEqualTo("/internal/projects/" + projectId);
            assertThat(request.query()).isEqualTo("owner_id=" + account.id());
            assertIsServiceToken(request.authorization());
        });
        List<String> names = JsonPath.read(listBody(account.token()), "$.projects[*].name");
        assertThat(names).isEmpty();
    }

    @Test
    void aProjectStorageCannotClearIsNotDeleted() {
        String token = registeredToken();
        String projectId = createdId(token, "Thesis");
        storage.answerWith(500);

        delete(token, projectId).expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail")
                .isEqualTo("Storage Management couldn't clear project " + projectId + ", so it wasn't deleted");

        List<String> names = JsonPath.read(listBody(token), "$.projects[*].name");
        assertThat(names).containsExactly("Thesis");
    }

    @Test
    void anotherUsersProjectLooksTheSameAsAMissingOne() {
        String theirs = createdId(registeredToken(), "Their thesis");
        String missing = UUID.randomUUID().toString();
        String token = registeredToken();

        for (String id : List.of(theirs, missing)) {
            rename(token, id, "Mine now").expectStatus().isNotFound()
                    .expectBody().jsonPath("$.detail").isEqualTo("No project " + id);
            delete(token, id).expectStatus().isNotFound()
                    .expectBody().jsonPath("$.detail").isEqualTo("No project " + id);
        }
        assertThat(storage.requests()).isEmpty();
    }

    @Test
    void projectsNeedAUserToken() {
        String someProject = "/projects/" + UUID.randomUUID();
        client.get().uri("/projects").exchange().expectStatus().isUnauthorized();
        client.post().uri("/projects").contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Thesis"))
                .exchange().expectStatus().isUnauthorized();
        client.put().uri(someProject).contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Thesis"))
                .exchange().expectStatus().isUnauthorized();
        client.delete().uri(someProject).exchange().expectStatus().isUnauthorized();
        for (String token : badTokens()) {
            client.get().uri("/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                .exchange().expectStatus().isForbidden();
    }

    private record Account(String token, String id) {
    }

    // a fresh account per test, through the same register call the frontend makes
    private Account registered() {
        String body = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "researcher-" + UUID.randomUUID() + "@example.com",
                        "password", "correct horse battery"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return new Account(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.user.id"));
    }

    private String registeredToken() {
        return registered().token();
    }

    // what Storage Management's /internal/** takes: a service token signed with the shared secret, short-lived
    private void assertIsServiceToken(String authorization) {
        assertThat(authorization).startsWith("Bearer ");
        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).build()
                .parseSignedClaims(authorization.substring("Bearer ".length())).getPayload();
        assertThat(claims.get("role")).isEqualTo("service");
        assertThat(claims.getSubject()).isEqualTo("svc:users");
        assertThat(claims.getExpiration()).isBefore(Date.from(Instant.now().plus(Duration.ofMinutes(10))));
    }

    // what Updating and Research Evaluation mint for themselves: valid, but not a researcher
    private String serviceToken() {
        return Jwts.builder().subject("svc:updating").claim("role", "service")
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).compact();
    }

    // garbage, expired, signed with another key, a sub that isn't a user id, and no sub at all
    private List<String> badTokens() {
        var key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        String user = UUID.randomUUID().toString();
        return List.of(
                "not-a-jwt",
                Jwts.builder().subject(user).expiration(new Date(System.currentTimeMillis() - 60_000)).signWith(key).compact(),
                Jwts.builder().subject(user).signWith(Keys.hmacShaKeyFor(new byte[32])).compact(),
                Jwts.builder().subject("svc:updating").signWith(key).compact(),
                Jwts.builder().claim("role", "user").signWith(key).compact());
    }

    private RestTestClient.ResponseSpec create(String token, String name) {
        return client.post().uri("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .exchange();
    }

    private String createdId(String token, String name) {
        String body = create(token, name).expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.id");
    }

    private RestTestClient.ResponseSpec rename(String token, String id, String name) {
        return client.put().uri("/projects/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", name))
                .exchange();
    }

    private RestTestClient.ResponseSpec delete(String token, String id) {
        return client.delete().uri("/projects/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static int status(RestTestClient.ResponseSpec response) {
        return response.expectBody(String.class).returnResult().getStatus().value();
    }

    private static List<Integer> atOnce(List<Callable<Integer>> requests) throws InterruptedException {
        try (ExecutorService pool = Executors.newFixedThreadPool(requests.size())) {
            return pool.invokeAll(requests).stream().map(Future::resultNow).toList();
        }
    }

    private String listBody(String token) {
        return client.get().uri("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }
}
