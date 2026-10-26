package com.g4t1.users.project;

import com.jayway.jsonpath.JsonPath;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProjectTest {

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

    @Test
    void theListShowsYourProjectsNewestFirst() throws InterruptedException {
        String token = registeredToken();
        String first = "First " + UUID.randomUUID();
        String second = "Second " + UUID.randomUUID();
        create(token, first).expectStatus().isCreated();
        // created_at is set as each request arrives; keep the two apart so the order is certain
        Thread.sleep(5);
        create(token, second).expectStatus().isCreated();

        List<String> names = JsonPath.read(listBody(token), "$.projects[*].name");

        assertThat(names).containsSubsequence(second, first);
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

    // a fresh account per test, through the same register call the frontend makes
    private String registeredToken() {
        String body = client.post().uri("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", "researcher-" + UUID.randomUUID() + "@example.com",
                        "password", "correct horse battery"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class).returnResult().getResponseBody();
        return JsonPath.read(body, "$.token");
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

    private String listBody(String token) {
        return client.get().uri("/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }
}
