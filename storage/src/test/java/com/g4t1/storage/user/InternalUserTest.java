package com.g4t1.storage.user;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DELETE /internal/users/{id}: User Management clearing out everything of an account being deleted
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalUserTest {

    private static final byte[] PDF = "%PDF-1.7 stored copy".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LocalFileStore files;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private final UUID user = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        jdbc.update("delete from snapshots");
        jdbc.update("delete from drafts");
        papers.deleteAll();
    }

    @Test
    void clearingAUserDeletesTheirPapersInEveryProjectWithEverythingAttached() throws Exception {
        UUID paper = savePaper(user, UUID.randomUUID());
        String paperFile = files.save(PDF);
        jdbc.update("update papers set file_key = ? where id = ?", paperFile, paper);
        String documentFile = addHistory(paper);
        // null is the default project, which has no row in User Management
        UUID defaultProjectPaper = savePaper(user, null);
        UUID someoneElsesPaper = savePaper(UUID.randomUUID(), null);

        clear(user).andExpect(status().isNoContent());

        assertThat(papers.existsById(paper)).isFalse();
        assertThat(papers.existsById(defaultProjectPaper)).isFalse();
        assertThat(count("snapshots", paper)).isZero();
        assertThat(count("alerts", paper)).isZero();
        assertThat(count("reports", paper)).isZero();
        // only this paper's document: other test classes share the database and leave theirs
        assertThat(jdbc.queryForObject("select count(*) from report_documents where file_key = ?", Integer.class, documentFile)).isZero();
        assertThat(files.read(paperFile)).isEmpty();
        assertThat(files.read(documentFile)).isEmpty();

        assertThat(papers.existsById(someoneElsesPaper)).isTrue();
    }

    @Test
    void clearingAUserDeletesTheirDraftsInEveryProject() throws Exception {
        String draftFile = saveDraft(user, UUID.randomUUID());
        String defaultProjectDraftFile = saveDraft(user, null);
        UUID someoneElse = UUID.randomUUID();
        String someoneElsesDraftFile = saveDraft(someoneElse, null);

        clear(user).andExpect(status().isNoContent());

        assertThat(drafts(user)).isZero();
        assertThat(files.read(draftFile)).isEmpty();
        assertThat(files.read(defaultProjectDraftFile)).isEmpty();

        assertThat(drafts(someoneElse)).isEqualTo(1);
        assertThat(files.read(someoneElsesDraftFile)).isPresent();
    }

    // safe to repeat, so User Management can retry a delete that failed halfway
    @Test
    void clearingAUserWithNothingStoredIsStillNoContent() throws Exception {
        clear(user).andExpect(status().isNoContent());
    }

    private ResultActions clear(UUID userId) throws Exception {
        return mvc.perform(delete("/internal/users/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    // a fresh DOI each time, since an owner can't track one DOI twice
    private UUID savePaper(UUID owner, UUID project) {
        Paper paper = new Paper(owner);
        paper.setDoi("10.1000/" + UUID.randomUUID());
        paper.setProjectId(project);
        return papers.save(paper).getId();
    }

    // a snapshot, an alert, and a report with a stored document; returns the document's file
    private String addHistory(UUID paper) {
        jdbc.update("""
                insert into snapshots (paper_id, doi, fetched_at, source_status)
                values (?, '10.1016/s0140-6736(20)31180-6', current_timestamp, '{}')""", paper);
        Long snapshotId = jdbc.queryForObject("select snapshot_id from snapshots where paper_id = ?", Long.class, paper);
        jdbc.update("""
                insert into alerts (paper_id, change_type, change_key, alert_severity, description, recommendation,
                                    detected_at, snapshot_id, previous_snapshot_id, created_at)
                values (?, 'retraction', 'openalex:is_retracted', 'high', 'd', 'r', current_timestamp, ?, ?, current_timestamp)
                """, paper, snapshotId, snapshotId);
        jdbc.update("insert into reports (paper_id, created_at) values (?, current_timestamp)", paper);
        String documentFile = files.save(PDF);
        jdbc.update("""
                insert into report_documents (report_id, kind, doi, crossref_status, text_status, pdf_status, file_key, created_at)
                select id, 'notice', '10.1016/s0140-6736(20)31324-6', 'ok', 'none', 'stored', ?, current_timestamp
                from reports where paper_id = ?""", documentFile, paper);
        return documentFile;
    }

    private String saveDraft(UUID owner, UUID project) {
        String file = files.save(PDF);
        jdbc.update("insert into drafts (id, owner_id, project_id, file_key, filename, uploaded_at) values (?, ?, ?, ?, 'draft.pdf', current_timestamp)",
                UUID.randomUUID(), owner, project, file);
        return file;
    }

    private int count(String table, UUID paper) {
        return jdbc.queryForObject("select count(*) from " + table + " where paper_id = ?", Integer.class, paper);
    }

    private int drafts(UUID owner) {
        return jdbc.queryForObject("select count(*) from drafts where owner_id = ?", Integer.class, owner);
    }
}
