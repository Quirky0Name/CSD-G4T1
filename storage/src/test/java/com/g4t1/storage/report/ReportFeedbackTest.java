package com.g4t1.storage.report;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.alert.Alert;
import com.g4t1.storage.alert.AlertRepository;
import com.g4t1.storage.alert.AlertSeverity;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PUT and DELETE /papers/{paperId}/reports/{reportId}/feedback: the researcher's verdict and note on a report
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportFeedbackTest {

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
    }

    @Test
    void theOwnerSavesFeedbackOnAnAssessedReportAndTheListShowsIt() throws Exception {
        long reportId = assessedReport(paperId);

        String saved = save(paperId, reportId, owner, "{\"verdict\": \"overstated\", \"note\": \"only use their dataset\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("overstated"))
                .andExpect(jsonPath("$.note").value("only use their dataset"))
                .andExpect(jsonPath("$.updated_at").isString())
                .andReturn().getResponse().getContentAsString();

        list(paperId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].feedback.verdict").value("overstated"))
                .andExpect(jsonPath("$.reports[0].feedback.note").value("only use their dataset"))
                .andExpect(jsonPath("$.reports[0].feedback.updated_at")
                        .value(json.readTree(saved).get("updated_at").asString()));
        readInternal(reportId)
                .andExpect(jsonPath("$.feedback.verdict").value("overstated"))
                .andExpect(jsonPath("$.feedback.note").value("only use their dataset"));
    }

    @Test
    void savingAgainReplacesVerdictAndNoteAndMovesTheTime() throws Exception {
        long reportId = assessedReport(paperId);
        save(paperId, reportId, owner, "{\"verdict\": \"overstated\", \"note\": \"first\"}").andExpect(status().isOk());
        Instant first = reports.findById(reportId).orElseThrow().getFeedbackUpdatedAt();

        save(paperId, reportId, owner, "{\"verdict\": \"fair\", \"note\": \"second\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("fair"))
                .andExpect(jsonPath("$.note").value("second"));

        Report report = reports.findById(reportId).orElseThrow();
        assertThat(report.getFeedbackVerdict()).isEqualTo(FeedbackVerdict.FAIR);
        assertThat(report.getFeedbackNote()).isEqualTo("second");
        assertThat(report.getFeedbackUpdatedAt()).isAfter(first);
    }

    @Test
    void aBlankOrAbsentNoteIsStoredAsNull() throws Exception {
        long reportId = assessedReport(paperId);

        save(paperId, reportId, owner, "{\"verdict\": \"understated\", \"note\": \"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(nullValue()));
        save(paperId, reportId, owner, "{\"verdict\": \"understated\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(nullValue()));
        save(paperId, reportId, owner, "{\"verdict\": \"understated\", \"note\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(nullValue()));
        list(paperId).andExpect(jsonPath("$.reports[0].feedback.note").value(nullValue()));
    }

    @Test
    void theNoteIsTrimmedAndMayBeFiveHundredCharacters() throws Exception {
        long reportId = assessedReport(paperId);
        String note = "n".repeat(500);

        save(paperId, reportId, owner, "{\"verdict\": \"fair\", \"note\": \"  " + note + "  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value(note));
    }

    @Test
    void badBodiesAreBadRequestsAndStoreNothing() throws Exception {
        long reportId = assessedReport(paperId);

        save(paperId, reportId, owner, "{\"verdict\": \"wrong\"}").andExpect(status().isBadRequest());
        save(paperId, reportId, owner, "{\"note\": \"no verdict\"}").andExpect(status().isBadRequest());
        save(paperId, reportId, owner, "{\"verdict\": \"fair\", \"note\": \"" + "n".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isString());

        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNull();
    }

    @Test
    void anotherUsersPaperAndAReportOfAnotherPaperAreNotFound() throws Exception {
        long reportId = assessedReport(paperId);
        UUID otherPaper = papers.save(new Paper(owner)).getId();
        String body = "{\"verdict\": \"fair\"}";

        save(paperId, reportId, UUID.randomUUID(), body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + paperId));
        save(otherPaper, reportId, owner, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report " + reportId));
        save(UUID.randomUUID(), reportId, owner, body).andExpect(status().isNotFound());
        save(paperId, 999_999, owner, body).andExpect(status().isNotFound());

        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNull();
    }

    @Test
    void aReportThatIsntAssessedIsAConflict() throws Exception {
        alert(paperId, "retraction");
        long reportId = reportService.open(paperId).orElseThrow().id();
        reportService.changeStatus(paperId, reportId, ReportStatus.INVESTIGATED);

        save(paperId, reportId, owner, "{\"verdict\": \"fair\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Report " + reportId + " is not assessed"));
    }

    @Test
    void savingAndDeletingNeedAUserToken() throws Exception {
        long reportId = assessedReport(paperId);
        String url = url(paperId, reportId);

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("{\"verdict\": \"fair\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete(url)).andExpect(status().isUnauthorized());
        mvc.perform(put(url).header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\": \"fair\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete(url).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isNull();
    }

    @Test
    void theOwnerDeletesFeedbackAndBothReadsThenShowNone() throws Exception {
        long reportId = assessedReport(paperId);
        save(paperId, reportId, owner, "{\"verdict\": \"overstated\", \"note\": \"x\"}").andExpect(status().isOk());

        remove(paperId, reportId, owner).andExpect(status().isNoContent());

        list(paperId).andExpect(jsonPath("$.reports[0].feedback").value(nullValue()));
        readInternal(reportId).andExpect(jsonPath("$.feedback").value(nullValue()));
        Report report = reports.findById(reportId).orElseThrow();
        assertThat(report.getFeedbackNote()).isNull();
        assertThat(report.getFeedbackUpdatedAt()).isNull();
    }

    @Test
    void deletingWhenThereIsNoneIsStillNoContent() throws Exception {
        long reportId = assessedReport(paperId);

        remove(paperId, reportId, owner).andExpect(status().isNoContent());
    }

    @Test
    void deletingOnAnotherUsersPaperIsNotFoundAndKeepsTheFeedback() throws Exception {
        long reportId = assessedReport(paperId);
        save(paperId, reportId, owner, "{\"verdict\": \"fair\"}").andExpect(status().isOk());
        UUID otherPaper = papers.save(new Paper(owner)).getId();

        remove(paperId, reportId, UUID.randomUUID()).andExpect(status().isNotFound());
        remove(otherPaper, reportId, owner).andExpect(status().isNotFound());

        assertThat(reports.findById(reportId).orElseThrow().getFeedbackVerdict()).isEqualTo(FeedbackVerdict.FAIR);
    }

    // a report of the paper, investigated and then assessed with a full evaluation
    long assessedReport(UUID paper) {
        alert(paper, "retraction");
        long reportId = reportService.open(paper).orElseThrow().id();
        reportService.changeStatus(paper, reportId, ReportStatus.INVESTIGATED);
        reportService.recordEvaluation(reportId, new ReportEvaluationRequest("The paper was retracted.",
                AssessmentLevel.HIGH, AssessmentLevel.MEDIUM, "Your Discussion relies on it.",
                "Replace the citation.", null, null));
        return reportId;
    }

    private void alert(UUID paper, String changeKey) {
        alerts.save(new Alert(paper, new NewAlertRequest(ChangeType.RETRACTION, changeKey, AlertSeverity.HIGH,
                "Something changed.", "Read the notice.", "10.1/notice", Instant.parse("2026-09-20T12:00:00Z"),
                42L, 41L)));
    }

    private static String url(UUID paper, long reportId) {
        return "/papers/" + paper + "/reports/" + reportId + "/feedback";
    }

    private ResultActions save(UUID paper, long reportId, UUID user, String body) throws Exception {
        return mvc.perform(put(url(paper, reportId)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions remove(UUID paper, long reportId, UUID user) throws Exception {
        return mvc.perform(delete(url(paper, reportId)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions list(UUID paper) throws Exception {
        return mvc.perform(get("/papers/" + paper + "/reports").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)));
    }

    private ResultActions readInternal(long reportId) throws Exception {
        return mvc.perform(get("/internal/reports/" + reportId).header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }
}
