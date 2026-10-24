package com.g4t1.users;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// Real HTTP rather than MockMvc, so the servlet container's own error handling is in the path.
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ErrorResponseTest {

    @LocalServerPort
    private int port;

    @Test
    void unknownAddressAnswersNotFoundAsProblemDetails() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/no-such-page"))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                contentType -> assertThat(contentType).startsWith("application/problem+json"));
        assertThat(JsonPath.<Integer>read(response.body(), "$.status")).isEqualTo(404);
    }
}
