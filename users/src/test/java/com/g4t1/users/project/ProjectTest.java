package com.g4t1.users.project;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

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
