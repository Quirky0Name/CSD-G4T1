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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// FeedbackNudgeTest's failure case with nothing listening at Research Evaluation's address
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeedbackNudgeUnreachableTest {

    @DynamicPropertySource
    static void researchEvaluation(DynamicPropertyRegistry registry) {
        registry.add("storage.re-base-url", () -> "http://localhost:" + closedPort());
    }

    // a port that was free a moment ago, so nothing answers there
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    MockMvc mvc;

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
    }

    @Test
    void anUnreachableResearchEvaluationNeverFailsTheSaveOrTheDelete() throws Exception {
        alerts.save(new Alert(paperId, new NewAlertRequest(ChangeType.RETRACTION, "retraction", AlertSeverity.HIGH,
                "Something changed.", "Read the notice.", "10.1/notice", Instant.parse("2026-09-20T12:00:00Z"),
                42L, 41L)));
        long reportId = reportService.open(paperId).orElseThrow().id();
        reportService.changeStatus(paperId, reportId, ReportStatus.INVESTIGATED);
        reportService.recordEvaluation(reportId, new ReportEvaluationRequest("Retracted.", AssessmentLevel.HIGH,
                AssessmentLevel.HIGH, "Your Methods use it.", "Remove it.", null, null));
        String url = "/papers/" + paperId + "/reports/" + reportId + "/feedback";

        mvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\": \"fair\"}"))
                .andExpect(status().isOk());
        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNotNull();

        mvc.perform(delete(url).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNoContent());
        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNull();
    }
}
