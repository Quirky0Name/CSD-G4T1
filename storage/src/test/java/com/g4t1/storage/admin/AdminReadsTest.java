package com.g4t1.storage.admin;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.alert.AlertService;
import com.g4t1.storage.alert.AlertSeverity;
import com.g4t1.storage.alert.AlertStatus;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.report.ReportService;
import com.g4t1.storage.snapshot.SnapshotRepository;
import com.g4t1.storage.snapshot.SnapshotRequest;
import com.g4t1.storage.snapshot.SnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The admin's reads on any researcher's papers, and clearing a paper's history
@SpringBootTest(properties = "storage.dev-mode=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminReadsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    SnapshotRepository snapshots;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    AlertService alertService;

    @Autowired
    ReportService reportService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;

    @BeforeEach
    void setUp() {
        paperId = savePaper(owner, "10.1000/one");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        snapshots.deleteAll();
        papers.deleteAll();
    }

    @Test
    void anOwnersPapersComeBackNewestFirstWithTheirOwnerAndLatestSnapshot() throws Exception {
        storeSnapshot(paperId, 100);
        storeSnapshot(paperId, 200);
        UUID newer = savePaper(owner, "10.1000/two");
        savePaper(UUID.randomUUID(), "10.1000/three");

        as(get("/admin/users/{ownerId}/papers", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(2))
                .andExpect(jsonPath("$.papers[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.papers[0].owner_id").value(owner.toString()))
                .andExpect(jsonPath("$.papers[0].latest_snapshot").value(nullValue()))
                .andExpect(jsonPath("$.papers[1].id").value(paperId.toString()))
                .andExpect(jsonPath("$.papers[1].doi").value("10.1000/one"))
                .andExpect(jsonPath("$.papers[1].file_available").value(false))
                .andExpect(jsonPath("$.papers[1].created_at").isNotEmpty())
                .andExpect(jsonPath("$.papers[1].owner_id").value(owner.toString()))
                .andExpect(jsonPath("$.papers[1].latest_snapshot.cited_by_count").value(200));
    }

    @Test
    void anUnknownOwnerHasNoPapers() throws Exception {
        as(get("/admin/users/{ownerId}/papers", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers.length()").value(0));
    }

    @Test
    void anotherUsersSnapshotsComeBackOldestFirst() throws Exception {
        storeSnapshot(paperId, 100);
        storeSnapshot(paperId, 200);

        as(get("/admin/papers/{id}/snapshots", paperId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].cited_by_count").value(100))
                .andExpect(jsonPath("$.snapshots[1].cited_by_count").value(200));
    }

    @Test
    void anotherUsersAlertsComeBackWithDismissedOnesIncluded() throws Exception {
        long kept = storeAlert("retraction");
        long dismissed = storeAlert("correction:10.1000/c");
        alertService.changeStatus(owner, dismissed, AlertStatus.DISMISSED);

        as(get("/admin/papers/{id}/alerts", paperId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts.length()").value(2))
                .andExpect(jsonPath("$.alerts[?(@.id == " + kept + ")].status").value("new"))
                .andExpect(jsonPath("$.alerts[?(@.id == " + dismissed + ")].status").value("dismissed"));
    }

    @Test
    void anotherUsersReportsComeBackInTheResearchersShape() throws Exception {
        long alert = storeAlert("retraction");
        reportService.open(paperId);

        as(get("/admin/papers/{id}/reports", paperId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(1))
                .andExpect(jsonPath("$.reports[0].paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.reports[0].status").value("investigating"))
                .andExpect(jsonPath("$.reports[0].alerts[0].id").value(alert))
                .andExpect(jsonPath("$.reports[0].documents.length()").value(0));
    }

    @Test
    void clearingHistoryDeletesSnapshotsAlertsAndReportsAndKeepsThePaper() throws Exception {
        storeSnapshot(paperId, 100);
        storeAlert("retraction");
        reportService.open(paperId);

        as(delete("/admin/papers/{id}/history", paperId)).andExpect(status().isNoContent());

        as(get("/admin/papers/{id}/snapshots", paperId)).andExpect(jsonPath("$.snapshots.length()").value(0));
        as(get("/admin/papers/{id}/alerts", paperId)).andExpect(jsonPath("$.alerts.length()").value(0));
        as(get("/admin/papers/{id}/reports", paperId)).andExpect(jsonPath("$.reports.length()").value(0));
        as(get("/admin/users/{ownerId}/papers", owner)).andExpect(jsonPath("$.papers.length()").value(1));
    }

    @Test
    void everyCallAnswersNoTokenAndTheWrongTokensAlike() throws Exception {
        for (MockHttpServletRequestBuilder call : calls(paperId)) {
            mvc.perform(call).andExpect(status().isUnauthorized());
            mvc.perform(call.header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                    .andExpect(status().isForbidden());
        }
        for (MockHttpServletRequestBuilder call : calls(paperId)) {
            mvc.perform(call.header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void everyPaperRouteAnswersAnUnknownPaperWithNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        for (MockHttpServletRequestBuilder call : paperCalls(unknown)) {
            as(call).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("No paper " + unknown));
        }
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        as(get("/admin/users/not-a-uuid/papers")).andExpect(status().isBadRequest());
        as(get("/admin/papers/not-a-uuid/snapshots")).andExpect(status().isBadRequest());
        as(get("/admin/papers/not-a-uuid/alerts")).andExpect(status().isBadRequest());
        as(get("/admin/papers/not-a-uuid/reports")).andExpect(status().isBadRequest());
        as(delete("/admin/papers/not-a-uuid/history")).andExpect(status().isBadRequest());
    }

    private List<MockHttpServletRequestBuilder> calls(UUID paper) {
        return List.of(
                get("/admin/users/{ownerId}/papers", owner),
                get("/admin/papers/{id}/snapshots", paper),
                get("/admin/papers/{id}/alerts", paper),
                get("/admin/papers/{id}/reports", paper),
                delete("/admin/papers/{id}/history", paper));
    }

    private static List<MockHttpServletRequestBuilder> paperCalls(UUID paper) {
        return List.of(
                get("/admin/papers/{id}/snapshots", paper),
                get("/admin/papers/{id}/alerts", paper),
                get("/admin/papers/{id}/reports", paper),
                delete("/admin/papers/{id}/history", paper));
    }

    private ResultActions as(MockHttpServletRequestBuilder call) throws Exception {
        return mvc.perform(call.header(HttpHeaders.AUTHORIZATION, TestTokens.admin(UUID.randomUUID())));
    }

    private UUID savePaper(UUID paperOwner, String doi) {
        Paper paper = new Paper(paperOwner);
        paper.setDoi(doi);
        return papers.saveAndFlush(paper).getId();
    }

    private void storeSnapshot(UUID paper, int citedByCount) {
        snapshotService.store(paper, new SnapshotRequest("10.1000/one", Instant.parse("2026-09-24T12:00:00Z"),
                null, null, null, false, null, true, null, null, null, null, null, null, citedByCount,
                json.readTree("{\"crossref\": \"ok\", \"openalex\": \"ok\", \"openalex_authors\": \"ok\"}")));
    }

    private long storeAlert(String changeKey) {
        return alertService.store(paperId, new NewAlertRequest(ChangeType.RETRACTION, changeKey, AlertSeverity.HIGH,
                "description", "recommendation", null, Instant.parse("2026-09-24T12:00:00Z"), 1L, 0L))
                .alert().id();
    }
}
