package com.g4t1.storage.report;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.alert.Alert;
import com.g4t1.storage.alert.AlertRepository;
import com.g4t1.storage.alert.AlertStatus;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.alert.Severity;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /internal/papers/{id}/reports, GET and PATCH .../reports/{reportId}
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalReportTest {

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
    TransactionTemplate tx;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ReportService reportService;

    @Autowired
    JdbcTemplate jdbc;

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
    void openingGroupsThePapersAlertsThatArentInAReport() throws Exception {
        long retraction = alert(paperId, "retraction");
        long correction = alert(paperId, "correction:10.1/c");
        UUID otherPaper = papers.save(new Paper(UUID.randomUUID())).getId();
        long otherPapers = alert(otherPaper, "retraction");

        open(paperId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.status").value("investigating"))
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.investigated_at").value(nullValue()))
                .andExpect(jsonPath("$.evaluation").value(nullValue()))
                .andExpect(jsonPath("$.recommendation").value(nullValue()))
                .andExpect(jsonPath("$.evaluated_at").value(nullValue()))
                .andExpect(jsonPath("$.change_summary").value(nullValue()))
                .andExpect(jsonPath("$.change_severity").value(nullValue()))
                .andExpect(jsonPath("$.impact_level").value(nullValue()))
                .andExpect(jsonPath("$.assessment").value(nullValue()))
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) retraction, (int) correction)))
                .andExpect(jsonPath("$.alerts[*].change_key").value(contains("retraction", "correction:10.1/c")))
                .andExpect(jsonPath("$.alerts[0].change_type").value("retraction"))
                .andExpect(jsonPath("$.alerts[0].severity").value("high"))
                .andExpect(jsonPath("$.alerts[0].description").value("Something changed."))
                .andExpect(jsonPath("$.alerts[0].notice_doi").value("10.1/notice"))
                .andExpect(jsonPath("$.alerts[0].detected_at").value("2026-09-20T12:00:00Z"))
                .andExpect(jsonPath("$.alerts[0].status").value("new"))
                .andExpect(jsonPath("$.documents").value(empty()));

        Report report = reports.findAll().getFirst();
        assertThat(report.getPaperId()).isEqualTo(paperId);
        assertThat(report.getStatus()).isEqualTo(ReportStatus.INVESTIGATING);
        assertThat(alerts.findById(retraction).orElseThrow().getReportId()).isEqualTo(report.getId());
        assertThat(alerts.findById(correction).orElseThrow().getReportId()).isEqualTo(report.getId());
        // another paper's alert stays out of it
        assertThat(alerts.findById(otherPapers).orElseThrow().getReportId()).isNull();
    }

    @Test
    void openingAgainWithNothingNewIsNoContentAndAddsNoReport() throws Exception {
        alert(paperId, "retraction");
        open(paperId).andExpect(status().isCreated());

        open(paperId)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        assertThat(reports.count()).isEqualTo(1);
    }

    @Test
    void aPaperWithNoAlertsGetsNoReport() throws Exception {
        open(paperId).andExpect(status().isNoContent());
        assertThat(reports.count()).isZero();
    }

    @Test
    void alertsStoredAfterAReportGoIntoTheNextReportOnly() throws Exception {
        long first = alert(paperId, "correction:10.1/c");
        open(paperId).andExpect(status().isCreated());
        long second = alert(paperId, "expression_of_concern:10.1/e");

        open(paperId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) second)));

        List<Report> both = reports.findAll();
        assertThat(both).hasSize(2);
        assertThat(alerts.findById(first).orElseThrow().getReportId()).isEqualTo(both.get(0).getId());
        assertThat(alerts.findById(second).orElseThrow().getReportId()).isEqualTo(both.get(1).getId());
    }

    @Test
    void aSecondAssignmentTakesNothing() {
        alert(paperId, "retraction");
        long firstReport = reports.save(new Report(paperId)).getId();
        long secondReport = reports.save(new Report(paperId)).getId();

        // the conditional update that stops two opens racing from sharing an alert
        int firstTook = tx.execute(status -> alerts.assignUnreportedToReport(paperId, firstReport));
        int secondTook = tx.execute(status -> alerts.assignUnreportedToReport(paperId, secondReport));

        assertThat(firstTook).isEqualTo(1);
        assertThat(secondTook).isZero();
    }

    @Test
    void aStatusChangeSavedAfterAnOpenKeepsTheAlertInItsReport() {
        long alertId = alert(paperId, "retraction");
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // the researcher's status change loads the alert, the nudge opens the report meanwhile,
        // then the status change commits
        tx.executeWithoutResult(status -> {
            Alert loaded = alerts.findById(alertId).orElseThrow();
            concurrent.executeWithoutResult(inner -> reportService.open(paperId));
            loaded.setStatus(AlertStatus.ACKNOWLEDGED);
        });

        Alert saved = alerts.findById(alertId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(saved.getReportId()).isEqualTo(reports.findAll().getFirst().getId());
    }

    @Test
    void openingForAnUnknownPaperIsNotFound() throws Exception {
        UUID missing = UUID.randomUUID();
        open(missing)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + missing));
    }

    @Test
    void readingAReportGivesItsAlertsDocumentsAndEmptyEvaluation() throws Exception {
        long retraction = alert(paperId, "retraction");
        long reportId = openedReportId();
        documents.saveAndFlush(new ReportDocument(documentRequest(reportId, "notice", "10.1/notice"), null));

        read(paperId, reportId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reportId))
                .andExpect(jsonPath("$.status").value("investigating"))
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) retraction)))
                .andExpect(jsonPath("$.documents[0].doi").value("10.1/notice"))
                .andExpect(jsonPath("$.documents[0].kind").value("notice"))
                .andExpect(jsonPath("$.documents[0].pdf_status").value("skipped"))
                .andExpect(jsonPath("$.evaluation").value(nullValue()))
                .andExpect(jsonPath("$.recommendation").value(nullValue()))
                .andExpect(jsonPath("$.evaluated_at").value(nullValue()))
                .andExpect(jsonPath("$.change_summary").value(nullValue()))
                .andExpect(jsonPath("$.change_severity").value(nullValue()))
                .andExpect(jsonPath("$.impact_level").value(nullValue()))
                .andExpect(jsonPath("$.assessment").value(nullValue()));
    }

    @Test
    void aReportShowsItsStoredAssessmentFields() throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();
        // nothing writes these yet (impact's endpoint comes later), so set the columns directly
        jdbc.update("update reports set change_summary = ?, change_severity = 'medium', impact_level = 'high', "
                        + "assessment = ? where id = ?",
                "The paper was corrected.", "{\"change\":{\"severity\":\"medium\"},\"usage\":null}", reportId);

        read(paperId, reportId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change_summary").value("The paper was corrected."))
                .andExpect(jsonPath("$.change_severity").value("medium"))
                .andExpect(jsonPath("$.impact_level").value("high"))
                .andExpect(jsonPath("$.assessment.change.severity").value("medium"))
                .andExpect(jsonPath("$.assessment.usage").value(nullValue()));
        Report report = reports.findById(reportId).orElseThrow();
        assertThat(report.getChangeSeverity()).isEqualTo(AssessmentLevel.MEDIUM);
        assertThat(report.getImpactLevel()).isEqualTo(AssessmentLevel.HIGH);
    }

    @Test
    void anUnknownReportOrAnotherPapersReportIsNotFound() throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();
        UUID otherPaper = papers.save(new Paper(UUID.randomUUID())).getId();

        read(paperId, reportId + 1000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report " + (reportId + 1000)));
        read(otherPaper, reportId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report " + reportId));
        UUID missing = UUID.randomUUID();
        read(missing, reportId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + missing));
    }

    @Test
    void aNonNumericReportIdIsABadRequest() throws Exception {
        mvc.perform(service(get("/internal/papers/" + paperId + "/reports/abc")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void markingInvestigatedSetsTheStatusAndKeepsTheFirstTime() throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();

        changeStatus(paperId, reportId, Map.of("status", "investigated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("investigated"))
                .andExpect(jsonPath("$.investigated_at").isString());
        Instant first = reports.findById(reportId).orElseThrow().getInvestigatedAt();
        assertThat(first).isNotNull();

        changeStatus(paperId, reportId, Map.of("status", "investigated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.investigated_at").value(first.toString()));
        assertThat(reports.findById(reportId).orElseThrow().getInvestigatedAt()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"assessed", "investigating", "done", "INVESTIGATED"})
    void onlyInvestigatedCanBeSet(String value) throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();

        changeStatus(paperId, reportId, Map.of("status", value)).andExpect(status().isBadRequest());
        assertThat(reports.findById(reportId).orElseThrow().getStatus()).isEqualTo(ReportStatus.INVESTIGATING);
    }

    @Test
    void aMissingStatusIsABadRequest() throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();

        changeStatus(paperId, reportId, Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void markingAnUnknownReportIsNotFound() throws Exception {
        changeStatus(paperId, 999_999, Map.of("status", "investigated"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report 999999"));
    }

    @Test
    void deletingThePaperDeletesItsReportsAndTheirDocuments() throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();
        documents.saveAndFlush(new ReportDocument(documentRequest(reportId, "notice", "10.1/notice"), null));

        // the alerts still point at the report when the paper goes
        papers.deleteById(paperId);

        assertThat(reports.count()).isZero();
        assertThat(documents.count()).isZero();
        assertThat(alerts.count()).isZero();
    }

    @Test
    void everyEndpointNeedsAServiceToken() throws Exception {
        alert(paperId, "retraction");
        long reportId = openedReportId();
        String reportUrl = "/internal/papers/" + paperId + "/reports";
        String body = json.writeValueAsString(Map.of("status", "investigated"));

        for (MockHttpServletRequestBuilder request : List.of(
                post(reportUrl),
                get(reportUrl + "/" + reportId),
                patch(reportUrl + "/" + reportId).contentType(MediaType.APPLICATION_JSON).content(body))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder request : List.of(
                post(reportUrl),
                get(reportUrl + "/" + reportId),
                patch(reportUrl + "/" + reportId).contentType(MediaType.APPLICATION_JSON).content(body))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                    .andExpect(status().isUnauthorized());
        }
        for (MockHttpServletRequestBuilder request : List.of(
                post(reportUrl),
                get(reportUrl + "/" + reportId),
                patch(reportUrl + "/" + reportId).contentType(MediaType.APPLICATION_JSON).content(body))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                    .andExpect(status().isForbidden());
        }
        assertThat(reports.findById(reportId).orElseThrow().getStatus()).isEqualTo(ReportStatus.INVESTIGATING);
    }

    private long alert(UUID paper, String changeKey) {
        return alerts.save(new Alert(paper, new NewAlertRequest(ChangeType.RETRACTION, changeKey, Severity.HIGH,
                "Something changed.", "Read the notice.", "10.1/notice", Instant.parse("2026-09-20T12:00:00Z"),
                42L, 41L))).getId();
    }

    private long openedReportId() throws Exception {
        String body = open(paperId).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    static NewReportDocumentRequest documentRequest(long reportId, String kind, String doi) {
        return new NewReportDocumentRequest(reportId, DocumentKind.of(kind), doi, CrossrefStatus.OK, null, null,
                TextStatus.NOT_OPEN_ACCESS, null, false);
    }

    private ResultActions open(UUID paper) throws Exception {
        return mvc.perform(service(post("/internal/papers/" + paper + "/reports")));
    }

    private ResultActions read(UUID paper, long reportId) throws Exception {
        return mvc.perform(service(get("/internal/papers/" + paper + "/reports/" + reportId)));
    }

    private ResultActions changeStatus(UUID paper, long reportId, Map<String, ?> body) throws Exception {
        return mvc.perform(service(patch("/internal/papers/" + paper + "/reports/" + reportId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))));
    }

    private static MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, TestTokens.service());
    }
}
