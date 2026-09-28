package com.g4t1.storage.dev;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevToolsTest {

    // the retracted Lancet paper as the real APIs report it: two retractions plus other notices
    private static final String NOTICES = """
            [{"notice_doi": "10.1016/s0140-6736(20)31324-6", "type": "retraction", "label": "Retraction", "source": "retraction-watch", "date": "2020-06-05", "record_id": 1},
             {"notice_doi": "10.1016/s0140-6736(20)31174-0", "type": "retraction", "label": "Retraction", "source": "publisher", "date": "2020-06-05", "record_id": null},
             {"notice_doi": "10.1016/s0140-6736(20)31324-6", "type": "erratum", "label": "Erratum", "source": "publisher", "date": "2020-06-05", "record_id": null},
             {"notice_doi": "10.1016/s0140-6736(20)31249-6", "type": "correction", "label": "Correction", "source": "retraction-watch", "date": "2020-06-01", "record_id": 2}]
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    SnapshotRepository snapshots;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private final UUID user = UUID.randomUUID();
    private UUID paperId;

    @BeforeEach
    void setUp() {
        paperId = savePaper(user, "10.1016/s0140-6736(20)31180-6");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        snapshots.deleteAll();
        papers.deleteAll();
    }

    @Test
    void undoingARetractionStoresTheSameDataNotRetractedWithOnlyItsNoticesRemoved() throws Exception {
        storeSnapshot(paperId, true);

        undo(paperId, "retraction", user).andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_retracted").value(false))
                .andExpect(jsonPath("$.crossref_updates[*].type").value(containsInAnyOrder("erratum", "correction")))
                .andExpect(jsonPath("$.title").value("Hydroxychloroquine or chloroquine"))
                .andExpect(jsonPath("$.source_status.openalex").value("ok"));
        assertThat(snapshots.count()).isEqualTo(2);
    }

    @Test
    void undoingACorrectionKeepsTheRetraction() throws Exception {
        storeSnapshot(paperId, true);

        undo(paperId, "correction", user).andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_retracted").value(true))
                .andExpect(jsonPath("$.crossref_updates[*].type").value(
                        containsInAnyOrder("retraction", "retraction", "erratum")));
    }

    @Test
    void aChangeThePaperDoesNotHaveIsAConflict() throws Exception {
        storeSnapshot(paperId, true);

        undo(paperId, "expression_of_concern", user).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Paper " + paperId + " has no expression_of_concern to undo"));
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test
    void aPaperThatWasNeverPolledIsAConflict() throws Exception {
        undo(paperId, "retraction", user).andExpect(status().isConflict());
    }

    @Test
    void anUnknownChangeIsABadRequest() throws Exception {
        storeSnapshot(paperId, true);

        undo(paperId, "typo", user).andExpect(status().isBadRequest());
    }

    @Test
    void anotherUsersPaperIsNotFound() throws Exception {
        storeSnapshot(paperId, true);

        undo(paperId, "retraction", UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + paperId));
        mvc.perform(delete("/dev/papers/{id}/history", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearingHistoryRemovesOnlyThatPapersSnapshotsAlertsAndReports() throws Exception {
        UUID otherPaper = savePaper(user, "10.1038/s41586-021-03819-2");
        long snapshotId = storeSnapshot(paperId, true);
        storeSnapshot(otherPaper, true);
        addAlertAndReport(paperId, snapshotId);

        mvc.perform(delete("/dev/papers/{id}/history", paperId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isNoContent());

        assertThat(count("background_metadata", paperId)).isZero();
        assertThat(count("alerts", paperId)).isZero();
        assertThat(count("reports", paperId)).isZero();
        assertThat(count("background_metadata", otherPaper)).isEqualTo(1);
    }

    @Test
    void swaggerIsServedWithoutATokenAndListsTheDevEndpoints() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/dev/papers/{paperId}/undo-change']").exists())
                .andExpect(jsonPath("$.paths['/papers']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }

    private ResultActions undo(UUID paper, String change, UUID caller) throws Exception {
        return mvc.perform(post("/dev/papers/{id}/undo-change", paper).param("change", change)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(caller)));
    }

    private UUID savePaper(UUID owner, String doi) {
        Paper paper = new Paper(owner);
        paper.setDoi(doi);
        return papers.save(paper).getId();
    }

    private long storeSnapshot(UUID paper, boolean retracted) {
        return snapshotService.store(paper, new SnapshotRequest("10.1016/s0140-6736(20)31180-6",
                Instant.parse("2026-09-27T12:00:00Z"), "W3027680906", "Hydroxychloroquine or chloroquine", 2020,
                retracted, json.readTree(NOTICES), false, "S49861241", "journal", "The Lancet", "0140-6736",
                "Elsevier BV", json.readTree("[]"), 1252,
                json.readTree("{\"crossref\": \"ok\", \"openalex\": \"ok\", \"openalex_authors\": \"ok\"}"))).snapshotId();
    }

    private void addAlertAndReport(UUID paper, long snapshotId) {
        jdbc.update("insert into reports (paper_id, created_at) values (?, current_timestamp)", paper);
        jdbc.update("""
                insert into alerts (paper_id, change_type, change_key, severity, description, recommendation,
                                    detected_at, snapshot_id, previous_snapshot_id, created_at)
                values (?, 'retraction', 'openalex:is_retracted', 'high', 'd', 'r', current_timestamp, ?, ?, current_timestamp)
                """, paper, snapshotId, snapshotId);
    }

    private int count(String table, UUID paper) {
        return jdbc.queryForObject("select count(*) from " + table + " where paper_id = ?", Integer.class, paper);
    }
}
