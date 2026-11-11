package com.g4t1.users.profile;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.g4t1.users.TestTokens.adminToken;
import static com.g4t1.users.TestTokens.badTokens;
import static com.g4t1.users.TestTokens.serviceToken;
import static org.assertj.core.api.Assertions.assertThat;

// GET and PUT /auth/me/profile: a researcher reading and replacing their own profile
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProfileTest {

    private static final Map<String, Object> FULL = Map.of(
            "occupation", "Other",
            "occupation_other", "Librarian",
            "research_fields", List.of("Medicine", "Psychology"),
            "research_interests", List.of("vaccine safety", "Ageing"),
            "work_types", List.of("Clinical"),
            "response_preferences", "Keep it brief and tell me what to do first.");

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    @Autowired
    private JdbcTemplate jdbc;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aSavedProfileIsReadBackInTheProfileShape() {
        Account account = registered(FULL);

        get(account.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.occupation").isEqualTo("Other")
                .jsonPath("$.occupation_other").isEqualTo("Librarian")
                .jsonPath("$.research_fields").isEqualTo(List.of("Medicine", "Psychology"))
                .jsonPath("$.research_interests").isEqualTo(List.of("vaccine safety", "Ageing"))
                .jsonPath("$.work_types").isEqualTo(List.of("Clinical"))
                .jsonPath("$.response_preferences").isEqualTo("Keep it brief and tell me what to do first.");
    }

    @Test
    void anAccountWithNoProfileIsNotFound() {
        Account account = registered(null);

        get(account.token())
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No profile " + account.id());
    }

    @Test
    void putReplacesEveryFieldAndClearsTheOnesLeftOut() {
        Account account = registered(FULL);
        Map<String, Object> edit = new HashMap<>();
        edit.put("occupation", "PhD student");
        edit.put("occupation_other", null);
        edit.put("research_interests", List.of("  cancer  "));

        String saved = put(account.token(), edit)
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat((String) JsonPath.read(saved, "$.occupation")).isEqualTo("PhD student");
        assertThat((Object) JsonPath.read(saved, "$.occupation_other")).isNull();
        assertThat((List<String>) JsonPath.read(saved, "$.research_fields")).isEmpty();
        assertThat((List<String>) JsonPath.read(saved, "$.research_interests")).containsExactly("cancer");
        assertThat((List<String>) JsonPath.read(saved, "$.work_types")).isEmpty();
        assertThat((Object) JsonPath.read(saved, "$.response_preferences")).isNull();
        get(account.token()).expectStatus().isOk().expectBody().json(saved);
    }

    @Test
    void clearingAnswersKeepsTheProfile() {
        Account account = registered(FULL);
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("occupation", null);
        cleared.put("research_fields", List.of());
        put(account.token(), cleared)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.occupation").isEqualTo(null)
                .jsonPath("$.research_fields").isEmpty();

        put(account.token(), Map.of()).expectStatus().isOk();

        get(account.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.occupation").isEqualTo(null)
                .jsonPath("$.occupation_other").isEqualTo(null)
                .jsonPath("$.research_fields").isEmpty()
                .jsonPath("$.research_interests").isEmpty()
                .jsonPath("$.work_types").isEmpty()
                .jsonPath("$.response_preferences").isEqualTo(null);
    }

    @Test
    void putOnAnAccountWithNoProfileCreatesIt() {
        Account account = registered(null);

        put(account.token(), Map.of("work_types", List.of("Theoretical"))).expectStatus().isOk();

        get(account.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.work_types").isEqualTo(List.of("Theoretical"));
    }

    // two tabs saving the first profile at once: both inserts race, and the loser updates the winner's row
    @Test
    void twoFirstSavesAtOnceBothSucceedAndLeaveOneProfile() throws InterruptedException {
        Account account = registered(null);
        Callable<Integer> save = () -> put(account.token(), Map.of("occupation", "Clinician"))
                .expectBody(String.class).returnResult().getStatus().value();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            statuses = pool.invokeAll(List.of(save, save)).stream().map(Future::resultNow).toList();
        }

        assertThat(statuses).containsExactly(200, 200);
        // the only way to count rows: the API shows one profile per account whatever is stored
        assertThat(jdbc.queryForObject("select count(*) from users.user_profiles where user_id = ?",
                Integer.class, UUID.fromString(account.id()))).isEqualTo(1);
    }

    @Test
    void aResearcherOnlyEverSeesAndChangesTheirOwnProfile() {
        Account alice = registered(FULL);
        Account bob = registered(null);

        get(bob.token()).expectStatus().isNotFound();
        put(bob.token(), Map.of("occupation", "Clinician")).expectStatus().isOk();

        get(alice.token()).expectStatus().isOk().expectBody().jsonPath("$.occupation").isEqualTo("Other");
        get(bob.token()).expectStatus().isOk().expectBody().jsonPath("$.occupation").isEqualTo("Clinician");
    }

    @Test
    void aMissingOrBadTokenIsUnauthorized() {
        client.get().uri("/auth/me/profile").exchange().expectStatus().isUnauthorized();
        client.put().uri("/auth/me/profile").contentType(MediaType.APPLICATION_JSON).body(Map.of())
                .exchange().expectStatus().isUnauthorized();
        for (String token : badTokens(jwtSecret)) {
            get(token).expectStatus().isUnauthorized();
            put(token, Map.of()).expectStatus().isUnauthorized();
        }
    }

    @Test
    void anAdminOrServiceTokenIsForbidden() {
        for (String token : List.of(adminToken(jwtSecret), serviceToken(jwtSecret))) {
            get(token).expectStatus().isForbidden();
            put(token, Map.of()).expectStatus().isForbidden();
        }
    }

    // the token outlives the account by up to its lifetime; a 401 makes the frontend sign it out
    @Test
    void aTokenWhoseAccountWasDeletedIsUnauthorized() {
        Account account = registered(FULL);
        jdbc.update("delete from users.users where id = ?", UUID.fromString(account.id()));

        get(account.token())
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Your account no longer exists");
        put(account.token(), Map.of("occupation", "Clinician"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Your account no longer exists");
    }

    @Test
    void theQuestionnaireRulesApplyToPut() {
        Account account = registered(FULL);

        put(account.token(), Map.of("response_preferences", "x".repeat(501)))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").value(String.class, d -> assertThat(d).startsWith("response_preferences"));
        put(account.token(), Map.of("research_interests", List.of("a", "b", "c", "d", "e", "f")))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, d -> assertThat(d).startsWith("research_interests"));
        put(account.token(), Map.of("occupation", "PhD student", "occupation_other", "Librarian"))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, d -> assertThat(d).startsWith("occupation_other"));

        // nothing was changed by the refused edits
        get(account.token()).expectStatus().isOk().expectBody().jsonPath("$.occupation").isEqualTo("Other");
    }

    private record Account(String token, String id) {
    }

    private Account registered(Map<String, Object> profile) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", "researcher-" + UUID.randomUUID() + "@example.com");
        body.put("password", "correct horse battery");
        body.put("profile", profile);
        String reply = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return new Account(JsonPath.read(reply, "$.token"), JsonPath.read(reply, "$.user.id"));
    }

    private RestTestClient.ResponseSpec get(String token) {
        return client.get().uri("/auth/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private RestTestClient.ResponseSpec put(String token, Map<String, ?> body) {
        return client.put().uri("/auth/me/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }
}
