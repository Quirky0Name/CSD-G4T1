package com.g4t1.storage.evaluation;

import com.g4t1.storage.security.ServiceTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * Storage Management's calls to Research Evaluation (RE_BASE_URL), with a service token it mints. So far
 * only the feedback nudge, POST /embed/feedback, which embeds the report's feedback as its chunk.
 */
@Component
public class ResearchEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(ResearchEvaluationClient.class);
    // Research Evaluation answers 202 before it embeds anything
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient rest;
    private final ServiceTokens tokens;

    public ResearchEvaluationClient(RestClient.Builder builder, ServiceTokens tokens,
                                    @Value("${storage.re-base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(TIMEOUT);
        requests.setReadTimeout(TIMEOUT);
        this.rest = builder.baseUrl(baseUrl).requestFactory(requests).build();
        this.tokens = tokens;
    }

    /**
     * Asks Research Evaluation to re-embed the report's feedback chunk (an empty one when the feedback was
     * deleted). Never throws: a failure is logged, the saved feedback stands, and saving again re-embeds.
     */
    public void nudgeFeedback(long reportId) {
        try {
            rest.post()
                    .uri("/embed/feedback")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.mint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("report_id", reportId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Nudging Research Evaluation to embed report {}'s feedback failed: {}", reportId,
                    e.getClass().getSimpleName());
        }
    }
}
