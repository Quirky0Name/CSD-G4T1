package com.g4t1.storage.evaluation;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.alert.Alert;
import com.g4t1.storage.alert.AlertRepository;
import com.g4t1.storage.alert.AlertSeverity;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.report.AssessmentLevel;
import com.g4t1.storage.report.ReportDocumentRepository;
import com.g4t1.storage.report.ReportEvaluationRequest;
import com.g4t1.storage.report.ReportRepository;
import com.g4t1.storage.report.ReportService;
import com.g4t1.storage.report.ReportStatus;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Saving or deleting feedback nudges Research Evaluation's POST /embed/feedback, here a real HTTP
// server on a free port that records what it gets and answers with `answer`
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedbackNudgeTest {

    // the key in application-test.yml
    private static final String SECRET = "c3RvcmFnZS10ZXN0LXNlY3JldC1ub3QtZm9yLXByb2Qh";
    // how long a nudge may take to arrive, and how long to wait for one that must not
    private static final long ARRIVES_SECONDS = 5;
    private static final long NONE_MILLIS = 700;

    record Received(String method, String path, String authorization, String body) {
    }

    private static final BlockingQueue<Received> RECEIVED = new LinkedBlockingQueue<>();
    private static volatile int answer = 202;
    private static final HttpServer RE = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION), body));
                exchange.sendResponseHeaders(answer, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void researchEvaluation(DynamicPropertyRegistry registry) {
        registry.add("storage.re-base-url", () -> "http://localhost:" + RE.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        RE.stop(0);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertRepository alerts;

    @Autowired
    ReportRepository reports;

    @Autowired
    ReportDocumentRepository documents;

    @Autowired
    ReportService reportService;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;

    @BeforeEach
    void clean() {
        documents.deleteAll();
        alerts.deleteAll();
        reports.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(owner)).getId();
        answer = 202;
        RECEIVED.clear();
    }

    @Test
    void savingNudgesWithAStorageServiceToken() throws Exception {
        long reportId = assessedReport();

        save(reportId, "{\"verdict\": \"overstated\", \"note\": \"only use their dataset\"}")
                .andExpect(status().isOk());

        Received nudge = nextNudge();
        assertThat(nudge.method()).isEqualTo("POST");
        assertThat(nudge.path()).isEqualTo("/embed/feedback");
        assertThat(json.readTree(nudge.body()).propertyNames()).containsExactly("report_id");
        assertThat(json.readTree(nudge.body()).get("report_id").asLong()).isEqualTo(reportId);
        assertThat(nudge.authorization()).startsWith("Bearer ");
        Claims claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET))).build()
                .parseSignedClaims(nudge.authorization().substring("Bearer ".length())).getPayload();
        assertThat(claims.getSubject()).isEqualTo("svc:storage");
        assertThat(claims.get("role", String.class)).isEqualTo("service");
        assertThat(Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(claims.getIssuedAt().toInstant()).isCloseTo(Instant.now(), within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertNoMoreNudges();
    }

    @Test
    void deletingNudgesToo() throws Exception {
        long reportId = assessedReport();
        save(reportId, "{\"verdict\": \"fair\"}").andExpect(status().isOk());
        nextNudge();

        remove(reportId).andExpect(status().isNoContent());

        Received nudge = nextNudge();
        assertThat(nudge.path()).isEqualTo("/embed/feedback");
        assertThat(json.readTree(nudge.body()).propertyNames()).containsExactly("report_id");
        assertThat(json.readTree(nudge.body()).get("report_id").asLong()).isEqualTo(reportId);
        assertNoMoreNudges();
    }

    @Test
    void aFailingResearchEvaluationNeverFailsTheSaveOrTheDelete() throws Exception {
        answer = 500;
        long reportId = assessedReport();

        save(reportId, "{\"verdict\": \"understated\"}").andExpect(status().isOk());
        nextNudge();
        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNotNull();

        remove(reportId).andExpect(status().isNoContent());
        nextNudge();
        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNull();
    }

    @Test
    void aRejectedSaveOrDeleteSendsNoNudge() throws Exception {
        long reportId = assessedReport();
        long investigated = investigatedReport();

        save(reportId, "{\"verdict\": \"wrong\"}").andExpect(status().isBadRequest());
        save(reportId, "{\"verdict\": \"fair\", \"note\": \"" + "n".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
        mvc.perform(put("/papers/" + paperId + "/reports/" + reportId + "/feedback")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\": \"fair\"}"))
                .andExpect(status().isNotFound());
        save(investigated, "{\"verdict\": \"fair\"}").andExpect(status().isConflict());
        mvc.perform(delete("/papers/" + paperId + "/reports/" + reportId + "/feedback")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        remove(999_999).andExpect(status().isNotFound());

        assertNoMoreNudges();
    }

    private Received nextNudge() throws InterruptedException {
        Received nudge = RECEIVED.poll(ARRIVES_SECONDS, TimeUnit.SECONDS);
        assertThat(nudge).as("a nudge to Research Evaluation").isNotNull();
        return nudge;
    }

    private void assertNoMoreNudges() throws InterruptedException {
        assertThat(RECEIVED.poll(NONE_MILLIS, TimeUnit.MILLISECONDS)).isNull();
    }

    private long assessedReport() {
        long reportId = investigatedReport();
        reportService.recordEvaluation(reportId, new ReportEvaluationRequest("The paper was retracted.",
                AssessmentLevel.HIGH, AssessmentLevel.MEDIUM, "Your Discussion relies on it.",
                "Replace the citation.", null, null));
        return reportId;
    }

    private long investigatedReport() {
        alerts.save(new Alert(paperId, new NewAlertRequest(ChangeType.RETRACTION, "retraction:" + UUID.randomUUID(),
                AlertSeverity.HIGH, "Something changed.", "Read the notice.", "10.1/notice",
                Instant.parse("2026-09-20T12:00:00Z"), 42L, 41L)));
        long reportId = reportService.open(paperId).orElseThrow().id();
        reportService.changeStatus(paperId, reportId, ReportStatus.INVESTIGATED);
        return reportId;
    }

    private ResultActions save(long reportId, String body) throws Exception {
        return mvc.perform(put("/papers/" + paperId + "/reports/" + reportId + "/feedback")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions remove(long reportId) throws Exception {
        return mvc.perform(delete("/papers/" + paperId + "/reports/" + reportId + "/feedback")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)));
    }
}
