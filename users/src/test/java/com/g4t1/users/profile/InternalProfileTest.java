package com.g4t1.users.profile;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.g4t1.users.TestTokens.adminToken;
import static com.g4t1.users.TestTokens.badTokens;
import static com.g4t1.users.TestTokens.serviceToken;

// GET /internal/users/{id}/profile: Research Evaluation reading a researcher's profile with its service token
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalProfileTest {

    @LocalServerPort
    private int port;

    @Value("${users.jwt-secret}")
    private String jwtSecret;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void aServiceReadsTheProfile() {
        Account account = registered(Map.of(
                "occupation", "PhD student",
                "research_fields", List.of("Medicine"),
                "research_interests", List.of("vaccine safety"),
                "response_preferences", "Brief."));

        read(account.id(), serviceToken(jwtSecret))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.occupation").isEqualTo("PhD student")
                .jsonPath("$.occupation_other").isEqualTo(null)
                .jsonPath("$.research_fields").isEqualTo(List.of("Medicine"))
                .jsonPath("$.research_interests").isEqualTo(List.of("vaccine safety"))
                .jsonPath("$.work_types").isEmpty()
                .jsonPath("$.response_preferences").isEqualTo("Brief.");
    }

    @Test
    void anAccountWithNoProfileIsNotFound() {
        Account account = registered(null);

        read(account.id(), serviceToken(jwtSecret))
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No profile " + account.id());
    }

    @Test
    void anUnknownUserIsTheSameNotFound() {
        String unknown = UUID.randomUUID().toString();

        read(unknown, serviceToken(jwtSecret))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("No profile " + unknown);
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() {
        read("not-a-uuid", serviceToken(jwtSecret))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aMissingOrBadTokenIsUnauthorized() {
        Account account = registered(Map.of("occupation", "Clinician"));

        client.get().uri("/internal/users/" + account.id() + "/profile").exchange()
                .expectStatus().isUnauthorized();
        for (String token : badTokens(jwtSecret)) {
            read(account.id(), token).expectStatus().isUnauthorized();
        }
    }

    // a researcher can't read anyone's profile here, their own included, and nor can the admin
    @Test
    void aUserOrAdminTokenIsForbidden() {
        Account account = registered(Map.of("occupation", "Clinician"));

        read(account.id(), account.token()).expectStatus().isForbidden();
        read(account.id(), adminToken(jwtSecret)).expectStatus().isForbidden();
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

    private RestTestClient.ResponseSpec read(String userId, String token) {
        return client.get().uri("/internal/users/" + userId + "/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }
}
