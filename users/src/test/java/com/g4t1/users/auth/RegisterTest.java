package com.g4t1.users.auth;

import com.g4t1.users.profile.UserProfileRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RegisterTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    // a real repository, made to fail in one test to show the account and profile are one transaction
    @MockitoSpyBean
    private UserProfileRepository profileRepository;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void registeringCreatesAnAccount() {
        String email = newEmail();

        register(email, "correct horse battery")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.user.id").value(String.class,
                        id -> assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException())
                .jsonPath("$.user.email").isEqualTo(email)
                .jsonPath("$.user.role").isEqualTo("user")
                .jsonPath("$.user.created_at").isNotEmpty()
                .jsonPath("$..password").doesNotExist();
    }

    @Test
    void anEmailThatAlreadyHasAnAccountIsAConflict() {
        String email = newEmail();
        register(email, "correct horse battery").expectStatus().isCreated();

        register(email, "a different password")
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("An account with this email already exists");
    }

    @Test
    void anEmailIsOneAccountWhateverItsLetterCase() {
        String email = newEmail();

        register(email.toUpperCase(), "correct horse battery")
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.user.email").isEqualTo(email);

        register(email, "a different password").expectStatus().isEqualTo(409);
    }

    // a double-click on sign-up: both requests get past the "already taken?" check before either is saved
    @Test
    void anEmailRegisteredTwiceAtOnceIsStillOneAccount() throws InterruptedException {
        String email = newEmail();
        Callable<Integer> attempt = () -> register(email, "correct horse battery")
                .expectBody(String.class).returnResult().getStatus().value();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            statuses = pool.invokeAll(List.of(attempt, attempt)).stream().map(Future::resultNow).toList();
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void anEmailThatIsNotAnAddressIsABadRequest() {
        register("not-an-email", "correct horse battery")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // the admin account's address has no @, so nobody can register it
    @Test
    void theAdminAddressIsABadRequest() {
        register("admin", "correct horse battery")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aPasswordUnderEightCharactersIsABadRequest() {
        register(newEmail(), "seven77")
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // BCrypt can't hash past 72 bytes; "é" is 2 bytes, so this is 37 characters but 74 bytes
    @Test
    void aPasswordOverSeventyTwoBytesIsABadRequest() {
        register(newEmail(), "é".repeat(37))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\": \"correct horse battery\"}",
            "{\"email\": \"someone@example.com\"}",
            "{\"email\": \"\", \"password\": \"correct horse battery\"}",
            "{\"email\": \"someone@example.com\", \"password\": \"        \"}"})
    void aMissingOrBlankEmailOrPasswordIsABadRequest(String body) {
        client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // The one test that reads the database directly: no reply ever contains the stored password,
    // so this is the only way to see it's kept as a BCrypt hash (an agreed exception to HTTP-only tests).
    @Test
    void aPasswordIsStoredOnlyAsItsBcryptHash() {
        String email = newEmail();
        register(email, "correct horse battery").expectStatus().isCreated();

        String stored = jdbc.queryForObject(
                "select password_hash from users.users where email = ?", String.class, email);

        assertThat(stored).isNotEqualTo("correct horse battery");
        assertThat(new BCryptPasswordEncoder().matches("correct horse battery", stored)).isTrue();
    }

    @Test
    void registeringLogsTheResearcherIn() {
        var result = register(newEmail(), "correct horse battery")
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult();
        String body = result.getResponseBody();

        TokenAssertions.assertIsTokenFor(JsonPath.read(body, "$.token"), JsonPath.read(body, "$.user.id"), jwtSecret);
        TokenAssertions.assertSetsRefreshCookie(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE), false);
    }

    private static final Map<String, Object> QUESTIONNAIRE = Map.of(
            "occupation", "PhD student",
            "research_fields", List.of("Medicine", "Psychology"),
            "research_interests", List.of("vaccine safety"),
            "work_types", List.of("Clinical"),
            "response_preferences", "Keep it brief.");

    @Test
    void registeringWithAQuestionnaireSavesItAsTheProfile() {
        String email = newEmail();

        register(email, "correct horse battery", QUESTIONNAIRE)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.token").isNotEmpty()
                .jsonPath("$.user.email").isEqualTo(email)
                .jsonPath("$.user.role").isEqualTo("user")
                .jsonPath("$.profile").doesNotExist();

        Map<String, Object> row = profileRow(email);
        assertThat(row).containsEntry("occupation", "PhD student")
                .containsEntry("occupation_other", null)
                .containsEntry("research_fields", "[\"Medicine\",\"Psychology\"]")
                .containsEntry("research_interests", "[\"vaccine safety\"]")
                .containsEntry("work_types", "[\"Clinical\"]")
                .containsEntry("response_preferences", "Keep it brief.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ", \"profile\": null"})
    void registeringWithoutAQuestionnaireSavesNoProfile(String profile) {
        String email = newEmail();

        registerJson("{\"email\": \"" + email + "\", \"password\": \"correct horse battery\"" + profile + "}")
                .expectStatus().isCreated();

        assertThat(profileRows(email)).isZero();
    }

    @Test
    void anEmptyQuestionnaireStillCreatesTheAccount() {
        String email = newEmail();

        register(email, "correct horse battery", Map.of()).expectStatus().isCreated();

        assertThat(profileRow(email)).containsEntry("occupation", null).containsEntry("research_fields", "[]");
    }

    @Test
    void aTakenEmailWithAQuestionnaireIsAConflictAndLeavesTheFirstProfile() {
        String email = newEmail();
        register(email, "correct horse battery", QUESTIONNAIRE).expectStatus().isCreated();

        register(email, "a different password", Map.of("occupation", "Clinician", "work_types", List.of("Theoretical")))
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("An account with this email already exists");

        assertThat(profileRow(email)).containsEntry("occupation", "PhD student")
                .containsEntry("work_types", "[\"Clinical\"]");
    }

    @Test
    void anInvalidQuestionnaireOnATakenEmailIsABadRequest() {
        String email = newEmail();
        register(email, "correct horse battery").expectStatus().isCreated();

        register(email, "a different password", Map.of("research_fields", List.of("Astrology")))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).startsWith("research_fields"));
    }

    @Test
    void anInvalidQuestionnaireOnANewEmailIsABadRequestAndCreatesNoAccount() {
        String email = newEmail();

        register(email, "correct horse battery", Map.of("occupation", "Astronaut"))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.detail").value(String.class, detail -> assertThat(detail).startsWith("occupation"));

        assertThat(accounts(email)).isZero();
    }

    @Test
    void aProfileThatFailsToSaveLeavesNoAccount() {
        doThrow(new IllegalStateException("profile save failed")).when(profileRepository).saveAndFlush(any());
        String email = newEmail();

        register(email, "correct horse battery", QUESTIONNAIRE).expectStatus().is5xxServerError();

        assertThat(accounts(email)).isZero();
    }

    private RestTestClient.ResponseSpec register(String email, String password, Map<String, Object> profile) {
        return client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password, "profile", profile))
                .exchange();
    }

    private RestTestClient.ResponseSpec registerJson(String body) {
        return client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();
    }

    // the profile isn't in register's reply, so its row is read directly, as the password hash is above
    private Map<String, Object> profileRow(String email) {
        return jdbc.queryForMap("select p.* from users.user_profiles p join users.users u on u.id = p.user_id "
                + "where u.email = ?", email);
    }

    private int profileRows(String email) {
        return jdbc.queryForObject("select count(*) from users.user_profiles p join users.users u "
                + "on u.id = p.user_id where u.email = ?", Integer.class, email);
    }

    private int accounts(String email) {
        return jdbc.queryForObject("select count(*) from users.users where email = ?", Integer.class, email);
    }

    private RestTestClient.ResponseSpec register(String email, String password) {
        return client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .exchange();
    }

    // a fresh address per test, so tests never collide in the shared database
    private static String newEmail() {
        return "researcher-" + UUID.randomUUID() + "@example.com";
    }
}
