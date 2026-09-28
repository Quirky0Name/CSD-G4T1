package com.g4t1.storage.report;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.alert.Alert;
import com.g4t1.storage.alert.AlertRepository;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.alert.Severity;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /internal/reports/{reportId} and PUT /internal/reports/{reportId}/evaluation
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalReportEvaluationTest {

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

    private UUID paperId;

    @BeforeEach
    void clean() {
        documents.deleteAll();
        alerts.deleteAll();
        reports.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(UUID.randomUUID())).getId();
    }

    @Test
    void readingByIdGivesTheSameBodyAsTheNestedRead() throws Exception {
        long alertId = alert("retraction");
        long reportId = openedReportId();
        documents.saveAndFlush(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "notice", "10.1/notice"), null));

        String flat = read(reportId).andExpect(status().isOk())
                .andExpect(jsonPath("$.paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) alertId)))
                .andExpect(jsonPath("$.documents[0].doi").value("10.1/notice"))
                .andReturn().getResponse().getContentAsString();
        String nested = mvc.perform(service(get("/internal/papers/" + paperId + "/reports/" + reportId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(flat)).isEqualTo(json.readTree(nested));
    }

    @Test
    void readingAnUnknownReportIsNoReport() throws Exception {
        read(999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report 999999"));
    }

    @Test
    void aReportOfADeletedPaperIsNoReport() throws Exception {
        alert("retraction");
        long reportId = openedReportId();
        papers.deleteById(paperId);

        read(reportId).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("No report " + reportId));
    }

    @Test
    void aNonNumericReportIdIsABadRequest() throws Exception {
        mvc.perform(service(get("/internal/reports/abc"))).andExpect(status().isBadRequest());
        mvc.perform(service(put("/internal/reports/abc/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(full()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFullEvaluationIsStoredAndTheReportAssessed() throws Exception {
        long reportId = investigatedReportId();
        Map<String, Object> body = full();

        record(reportId, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId))
                .andExpect(jsonPath("$.status").value("assessed"))
                .andExpect(jsonPath("$.evaluated_at").isString())
                .andExpect(jsonPath("$.change_summary").value("The paper was retracted for fabricated data."))
                .andExpect(jsonPath("$.change_severity").value("high"))
                .andExpect(jsonPath("$.impact_level").value("medium"))
                .andExpect(jsonPath("$.evaluation").value("Your Discussion relies on it."))
                .andExpect(jsonPath("$.recommendation").value("Replace the citation."));

        // the assessment comes back exactly as sent, nested objects and nulls included
        String nested = mvc.perform(service(get("/internal/papers/" + paperId + "/reports/" + reportId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("assessed"))
                .andExpect(jsonPath("$.change_severity").value("high"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(nested).get("assessment")).isEqualTo(json.valueToTree(body.get("assessment")));
        Report report = reports.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.ASSESSED);
        assertThat(report.getEvaluatedAt()).isNotNull();
    }

    @Test
    void aChangeThatIsntMeaningfulStoresOnlyTheSummaryAndSeverity() throws Exception {
        long reportId = investigatedReportId();

        record(reportId, Map.of("change_summary", "An author's affiliation was corrected.", "change_severity", "none"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("assessed"))
                .andExpect(jsonPath("$.evaluated_at").isString())
                .andExpect(jsonPath("$.change_summary").value("An author's affiliation was corrected."))
                .andExpect(jsonPath("$.change_severity").value("none"))
                .andExpect(jsonPath("$.impact_level").value(nullValue()))
                .andExpect(jsonPath("$.evaluation").value(nullValue()))
                .andExpect(jsonPath("$.recommendation").value(nullValue()))
                .andExpect(jsonPath("$.assessment").value(nullValue()));
    }

    static Stream<Map<String, Object>> badBodies() {
        return Stream.of(
                without("change_summary"),
                with("change_summary", " "),
                without("change_severity"),
                with("change_severity", "critical"),
                with("change_severity", "HIGH"),
                with("impact_level", "severe"),
                without("impact_level"),
                without("evaluation"),
                with("evaluation", "  "),
                without("recommendation"),
                with("recommendation", ""),
                // severity none with any of the rest set
                Map.of("change_summary", "s", "change_severity", "none", "impact_level", "none"),
                Map.of("change_summary", "s", "change_severity", "none", "evaluation", "e"),
                Map.of("change_summary", "s", "change_severity", "none", "recommendation", "r"));
    }

    @ParameterizedTest
    @MethodSource("badBodies")
    void aBadBodyIsABadRequestAndChangesNothing(Map<String, Object> body) throws Exception {
        long reportId = investigatedReportId();

        record(reportId, body).andExpect(status().isBadRequest());
        assertThat(reports.findById(reportId).orElseThrow().getStatus()).isEqualTo(ReportStatus.INVESTIGATED);
    }

    @Test
    void anInvestigatingReportIsAConflict() throws Exception {
        alert("retraction");
        long reportId = openedReportId();

        record(reportId, full())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Report " + reportId + " is not investigated yet"));
        Report report = reports.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo(ReportStatus.INVESTIGATING);
        assertThat(report.getChangeSummary()).isNull();
    }

    @Test
    void anAssessedReportKeepsItsFirstEvaluation() throws Exception {
        long reportId = investigatedReportId();
        record(reportId, full()).andExpect(status().isOk());
        Report first = reports.findById(reportId).orElseThrow();

        record(reportId, Map.of("change_summary", "Another summary.", "change_severity", "none"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Report " + reportId + " is already assessed"));
        Report after = reports.findById(reportId).orElseThrow();
        assertThat(after.getChangeSummary()).isEqualTo(first.getChangeSummary());
        assertThat(after.getChangeSeverity()).isEqualTo(AssessmentLevel.HIGH);
        assertThat(after.getEvaluatedAt()).isEqualTo(first.getEvaluatedAt());

        // investigation's PATCH still can't touch it
        mvc.perform(service(patch("/internal/papers/" + paperId + "/reports/" + reportId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"investigated\"}")))
                .andExpect(status().isConflict());
    }

    @Test
    void evaluatingAnUnknownReportIsNoReport() throws Exception {
        record(999_999, full())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report 999999"));
    }

    @Test
    void deletingThePaperStillDeletesAnAssessedReport() throws Exception {
        long reportId = investigatedReportId();
        record(reportId, full()).andExpect(status().isOk());

        papers.deleteById(paperId);

        assertThat(reports.findById(reportId)).isEmpty();
    }

    @Test
    void bothEndpointsNeedAServiceToken() throws Exception {
        long reportId = investigatedReportId();
        String body = json.writeValueAsString(full());

        mvc.perform(get("/internal/reports/" + reportId)).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/reports/" + reportId + "/evaluation")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String user = TestTokens.user(UUID.randomUUID());
        mvc.perform(get("/internal/reports/" + reportId).header(HttpHeaders.AUTHORIZATION, user))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/reports/" + reportId + "/evaluation").header(HttpHeaders.AUTHORIZATION, user)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(reports.findById(reportId).orElseThrow().getStatus()).isEqualTo(ReportStatus.INVESTIGATED);
    }

    static Map<String, Object> full() {
        Map<String, Object> assessment = new HashMap<>();
        assessment.put("prompt_version", 1);
        assessment.put("change", Map.of("severity", "high", "alerts", List.of(Map.of("alert_id", 7))));
        assessment.put("impact", null);
        Map<String, Object> body = new HashMap<>();
        body.put("change_summary", "The paper was retracted for fabricated data.");
        body.put("change_severity", "high");
        body.put("impact_level", "medium");
        body.put("evaluation", "Your Discussion relies on it.");
        body.put("recommendation", "Replace the citation.");
        body.put("assessment", assessment);
        return body;
    }

    private static Map<String, Object> without(String field) {
        Map<String, Object> body = full();
        body.remove(field);
        return body;
    }

    private static Map<String, Object> with(String field, Object value) {
        Map<String, Object> body = full();
        body.put(field, value);
        return body;
    }

    private long alert(String changeKey) {
        return alerts.save(new Alert(paperId, new NewAlertRequest(ChangeType.RETRACTION, changeKey, Severity.HIGH,
                "Something changed.", "Read the notice.", "10.1/notice", Instant.parse("2026-09-20T12:00:00Z"),
                42L, 41L))).getId();
    }

    private long openedReportId() throws Exception {
        String body = mvc.perform(service(post("/internal/papers/" + paperId + "/reports")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return node.get("id").asLong();
    }

    private long investigatedReportId() throws Exception {
        alert("retraction");
        long reportId = openedReportId();
        mvc.perform(service(patch("/internal/papers/" + paperId + "/reports/" + reportId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"investigated\"}")))
                .andExpect(status().isOk());
        return reportId;
    }

    private ResultActions read(long reportId) throws Exception {
        return mvc.perform(service(get("/internal/reports/" + reportId)));
    }

    private ResultActions record(long reportId, Map<String, ?> body) throws Exception {
        return mvc.perform(service(put("/internal/reports/" + reportId + "/evaluation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))));
    }

    private static MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, TestTokens.service());
    }
}
