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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /papers/{id}/reports: the frontend's view of a paper's reports, with their alerts and documents
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportListTest {

    private static final String PAPER_DOI = "10.1016/j.ijantimicag.2020.105949";
    private static final String NOTICE_RECORD = """
            {"doi": "10.1016/j.ijantimicag.2024.107416", "title": "Retraction notice", "published": "2024-12",
             "journal": "IJAA", "update_to": [{"doi": "10.1016/j.ijantimicag.2020.105949", "type": "retraction"}],
             "relation": {}}""";

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
    void reportsComeNewestFirstEachWithOnlyItsOwnAlertsAndDocuments() throws Exception {
        long retraction = alert(paperId, "retraction", Severity.HIGH, "2026-09-20T12:00:00Z", "10.1/r").getId();
        long first = open(paperId);
        document(first, "notice", "10.1/r");
        long correction = alert(paperId, "correction:10.1/c", Severity.MEDIUM, "2026-09-21T12:00:00Z", "10.1/c").getId();
        long second = open(paperId);
        document(second, "notice", "10.1/c");
        document(second, "current_version", PAPER_DOI);
        // another paper of the same owner has its own report, which isn't this paper's
        UUID otherPaper = papers.save(new Paper(owner)).getId();
        alert(otherPaper, "retraction", Severity.HIGH, "2026-09-22T12:00:00Z", "10.1/other");
        long others = open(otherPaper);
        document(others, "notice", "10.1/other");

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].id").value(contains((int) second, (int) first)))
                .andExpect(jsonPath("$.reports[*].paper_id").value(contains(paperId.toString(), paperId.toString())))
                .andExpect(jsonPath("$.reports[0].alerts[*].id").value(contains((int) correction)))
                .andExpect(jsonPath("$.reports[0].documents[*].doi").value(contains("10.1/c", PAPER_DOI)))
                .andExpect(jsonPath("$.reports[1].alerts[*].id").value(contains((int) retraction)))
                .andExpect(jsonPath("$.reports[1].documents[*].doi").value(contains("10.1/r")));
    }

    @Test
    void alertsHaveTheAlertApiShapeInTheAlertListOrderWithDismissedOnesIncluded() throws Exception {
        // stored in the order Research Evaluation detects them, so within one poll the ids don't follow severity
        String poll = "2026-09-10T00:00:00Z";
        Alert retraction = alert(paperId, "retraction", Severity.HIGH, poll, "10.1/r");
        long erratum = alert(paperId, "erratum:10.1/e", Severity.LOW, poll, "10.1/e").getId();
        long correction = alert(paperId, "correction:10.1/c", Severity.MEDIUM, poll, "10.1/c").getId();
        long concern = alert(paperId, "expression_of_concern:10.1/x", Severity.MEDIUM, poll, "10.1/x").getId();
        // a later detection comes first, whatever its severity
        long later = alert(paperId, "erratum:10.1/l", Severity.LOW, "2026-09-11T00:00:00Z", "10.1/l").getId();
        dismiss(retraction);
        open(paperId);

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports", hasSize(1)))
                .andExpect(jsonPath("$.reports[0].alerts[*].id").value(contains(
                        (int) later, retraction.getId().intValue(), (int) concern, (int) correction, (int) erratum)))
                .andExpect(jsonPath("$.reports[0].alerts[1].id").value(retraction.getId()))
                .andExpect(jsonPath("$.reports[0].alerts[1].paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.reports[0].alerts[1].change_type").value("retraction"))
                .andExpect(jsonPath("$.reports[0].alerts[1].severity").value("high"))
                .andExpect(jsonPath("$.reports[0].alerts[1].description").value("Description of retraction"))
                .andExpect(jsonPath("$.reports[0].alerts[1].recommendation").value("Recommendation for retraction"))
                .andExpect(jsonPath("$.reports[0].alerts[1].notice_doi").value("10.1/r"))
                .andExpect(jsonPath("$.reports[0].alerts[1].detected_at").value(poll))
                .andExpect(jsonPath("$.reports[0].alerts[1].status").value("dismissed"))
                .andExpect(jsonPath("$.reports[0].alerts[1].status_changed_at").value("2026-09-10T00:01:00Z"))
                .andExpect(jsonPath("$.reports[0].alerts[0].status").value("new"))
                .andExpect(jsonPath("$.reports[0].alerts[0].status_changed_at").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].alerts[1].change_key").doesNotExist())
                .andExpect(jsonPath("$.reports[0].alerts[1].snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.reports[0].alerts[1].previous_snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.reports[0].alerts[1].created_at").doesNotExist())
                .andExpect(jsonPath("$.reports[0].alerts[1].report_id").doesNotExist());
    }

    @Test
    void documentsHaveTheirStoredFieldsWithoutStorageColumnsOldestFirst() throws Exception {
        alert(paperId, "retraction", Severity.HIGH, "2026-09-20T12:00:00Z", "10.1016/j.ijantimicag.2024.107416");
        long reportId = open(paperId);
        ReportDocument notice = documents.saveAndFlush(new ReportDocument(new NewReportDocumentRequest(reportId,
                DocumentKind.NOTICE, "10.1016/j.ijantimicag.2024.107416", CrossrefStatus.OK, null, true,
                TextStatus.OK, "This article has been retracted.", true), NOTICE_RECORD));
        ReportDocument current = documents.saveAndFlush(new ReportDocument(new NewReportDocumentRequest(reportId,
                DocumentKind.CURRENT_VERSION, PAPER_DOI, CrossrefStatus.ERROR, null, null,
                TextStatus.NOT_OPEN_ACCESS, null, false), null));
        current.recordPdf("stored-key.pdf", "ab".repeat(32), "https://pmc.example/articles/PMC7/pdf/main.pdf");
        current = documents.saveAndFlush(current);

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[0].documents[*].id").value(contains(
                        notice.getId().intValue(), current.getId().intValue())))
                // the notice: its record as a JSON object, its text, and no PDF
                .andExpect(jsonPath("$.reports[0].documents[0].kind").value("notice"))
                .andExpect(jsonPath("$.reports[0].documents[0].doi").value("10.1016/j.ijantimicag.2024.107416"))
                .andExpect(jsonPath("$.reports[0].documents[0].crossref_status").value("ok"))
                .andExpect(jsonPath("$.reports[0].documents[0].crossref_record.title").value("Retraction notice"))
                .andExpect(jsonPath("$.reports[0].documents[0].crossref_record.update_to[0].type").value("retraction"))
                .andExpect(jsonPath("$.reports[0].documents[0].update_to_includes_paper").value(true))
                .andExpect(jsonPath("$.reports[0].documents[0].text_status").value("ok"))
                .andExpect(jsonPath("$.reports[0].documents[0].text").value("This article has been retracted."))
                .andExpect(jsonPath("$.reports[0].documents[0].text_truncated").value(true))
                .andExpect(jsonPath("$.reports[0].documents[0].pdf_status").value("skipped"))
                .andExpect(jsonPath("$.reports[0].documents[0].pdf_source_url").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].documents[0].created_at").value(notice.getCreatedAt().toString()))
                .andExpect(jsonPath("$.reports[0].documents[0].pdf_fetched_at").value(nullValue()))
                // the current copy: its PDF was downloaded, and where from
                .andExpect(jsonPath("$.reports[0].documents[1].kind").value("current_version"))
                .andExpect(jsonPath("$.reports[0].documents[1].crossref_status").value("error"))
                .andExpect(jsonPath("$.reports[0].documents[1].crossref_record").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].documents[1].update_to_includes_paper").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].documents[1].text_status").value("not_open_access"))
                .andExpect(jsonPath("$.reports[0].documents[1].text").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].documents[1].text_truncated").value(false))
                .andExpect(jsonPath("$.reports[0].documents[1].pdf_status").value("ok"))
                .andExpect(jsonPath("$.reports[0].documents[1].pdf_source_url")
                        .value("https://pmc.example/articles/PMC7/pdf/main.pdf"))
                .andExpect(jsonPath("$.reports[0].documents[1].pdf_fetched_at")
                        .value(current.getPdfFetchedAt().toString()))
                // Storage Management's and impact's columns, and the report id it's nested under, stay out
                .andExpect(jsonPath("$.reports[0].documents[1].file_key").doesNotExist())
                .andExpect(jsonPath("$.reports[0].documents[1].sha256").doesNotExist())
                .andExpect(jsonPath("$.reports[0].documents[1].report_id").doesNotExist());
    }

    @Test
    void eachReportHasItsStatusAndNoEvaluationYet() throws Exception {
        alert(paperId, "retraction", Severity.HIGH, "2026-09-20T12:00:00Z", "10.1/r");
        long investigated = open(paperId);
        reportService.changeStatus(paperId, investigated, ReportStatus.INVESTIGATED);
        alert(paperId, "correction:10.1/c", Severity.MEDIUM, "2026-09-21T12:00:00Z", "10.1/c");
        long investigating = open(paperId);
        Report done = reports.findById(investigated).orElseThrow();
        Report opened = reports.findById(investigating).orElseThrow();

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].id").value(contains((int) investigating, (int) investigated)))
                .andExpect(jsonPath("$.reports[0].status").value("investigating"))
                .andExpect(jsonPath("$.reports[0].created_at").value(opened.getCreatedAt().toString()))
                .andExpect(jsonPath("$.reports[0].investigated_at").value(nullValue()))
                .andExpect(jsonPath("$.reports[0].documents").value(empty()))
                .andExpect(jsonPath("$.reports[1].status").value("investigated"))
                .andExpect(jsonPath("$.reports[1].created_at").value(done.getCreatedAt().toString()))
                .andExpect(jsonPath("$.reports[1].investigated_at").value(done.getInvestigatedAt().toString()))
                .andExpect(jsonPath("$.reports[*].evaluation").value(contains(nullValue(), nullValue())))
                .andExpect(jsonPath("$.reports[*].recommendation").value(contains(nullValue(), nullValue())))
                .andExpect(jsonPath("$.reports[*].evaluated_at").value(contains(nullValue(), nullValue())));
    }

    @Test
    void aReportWhoseAlertMovedToALaterReportIsStillListedWithNoAlerts() throws Exception {
        // OpenAlex's flag first: a retraction alert with no notice, and the current copy fetched for it
        Alert flag = alert(paperId, "retraction", Severity.HIGH, "2026-09-20T12:00:00Z", null);
        long flagReport = open(paperId);
        document(flagReport, "current_version", PAPER_DOI);
        // the notice arrives later and replaces the alert, which leaves its report for the next one
        alerts.replaceNoticelessRetraction(flag.getId(), Severity.HIGH, "Retracted, with a notice.",
                "Read the notice.", "10.1/notice", Instant.parse("2026-09-21T12:00:00Z"), 44L, 43L, AlertStatus.NEW);
        long noticeReport = open(paperId);
        document(noticeReport, "notice", "10.1/notice");

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports[*].id").value(contains((int) noticeReport, (int) flagReport)))
                .andExpect(jsonPath("$.reports[0].alerts[*].id").value(contains(flag.getId().intValue())))
                .andExpect(jsonPath("$.reports[0].alerts[0].notice_doi").value("10.1/notice"))
                .andExpect(jsonPath("$.reports[1].alerts").value(empty()))
                .andExpect(jsonPath("$.reports[1].documents[*].doi").value(contains(PAPER_DOI)));
    }

    @Test
    void aPaperWithNoReportsGivesAnEmptyList() throws Exception {
        // an alert no report has grouped yet isn't in any report
        alert(paperId, "retraction", Severity.HIGH, "2026-09-20T12:00:00Z", "10.1/r");

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports").isArray())
                .andExpect(jsonPath("$.reports", hasSize(0)));
    }

    @Test
    void anotherUsersPaperLooksTheSameAsAMissingOne() throws Exception {
        alert(paperId, "retraction", Severity.HIGH, "2026-09-20T12:00:00Z", "10.1/r");
        open(paperId);
        UUID missing = UUID.randomUUID();

        list(paperId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + paperId))
                .andExpect(jsonPath("$.reports").doesNotExist());
        list(missing, owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + missing));
    }

    @Test
    void aPaperIdThatIsntAUuidIsABadRequest() throws Exception {
        mvc.perform(get("/papers/not-a-uuid/reports").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listingNeedsAUserToken() throws Exception {
        String url = "/papers/" + paperId + "/reports";

        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    private ResultActions list(UUID paper, UUID user) throws Exception {
        return mvc.perform(get("/papers/" + paper + "/reports").header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private Alert alert(UUID paper, String changeKey, Severity severity, String detectedAt, String noticeDoi) {
        String type = changeKey.split(":")[0];
        return alerts.save(new Alert(paper, new NewAlertRequest(ChangeType.of(type), changeKey, severity,
                "Description of " + type, "Recommendation for " + type, noticeDoi, Instant.parse(detectedAt),
                42L, 41L)));
    }

    private void dismiss(Alert alert) {
        alert.setStatus(AlertStatus.DISMISSED);
        alert.setStatusChangedAt(alert.getDetectedAt().plusSeconds(60));
        alerts.save(alert);
    }

    // groups the paper's alerts that aren't in a report yet, as Research Evaluation's open does
    private long open(UUID paper) {
        return reportService.open(paper).orElseThrow().id();
    }

    private void document(long reportId, String kind, String doi) {
        documents.saveAndFlush(new ReportDocument(InternalReportTest.documentRequest(reportId, kind, doi), null));
    }
}
