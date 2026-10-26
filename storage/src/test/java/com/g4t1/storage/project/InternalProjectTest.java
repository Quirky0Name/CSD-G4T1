package com.g4t1.storage.project;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DELETE /internal/projects/{id}?owner_id=: User Management clearing out a project the researcher deleted
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalProjectTest {

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

    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        jdbc.update("delete from snapshots");
        jdbc.update("delete from drafts");
        papers.deleteAll();
    }

    @Test
    void clearingAProjectDeletesItsPapersWithEverythingAttachedAndItsDraft() throws Exception {
        UUID paper = savePaper(owner, project);
        String paperFile = files.save(PDF);
        jdbc.update("update papers set file_key = ? where id = ?", paperFile, paper);
        String documentFile = addHistory(paper);
        String draftFile = saveDraft(owner, project);
        // the owner's other project, and another owner's project with the same id, stay as they are
        UUID otherProject = UUID.randomUUID();
        UUID keptPaper = savePaper(owner, otherProject);
        String keptDraftFile = saveDraft(owner, otherProject);
        UUID someoneElse = UUID.randomUUID();
        UUID someoneElsesPaper = savePaper(someoneElse, project);
        String someoneElsesDraftFile = saveDraft(someoneElse, project);

        clear(project, owner).andExpect(status().isNoContent());

        assertThat(papers.existsById(paper)).isFalse();
        assertThat(count("snapshots", paper)).isZero();
        assertThat(count("alerts", paper)).isZero();
        assertThat(count("reports", paper)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from report_documents", Integer.class)).isZero();
        assertThat(files.read(paperFile)).isEmpty();
        assertThat(files.read(documentFile)).isEmpty();
        assertThat(files.read(draftFile)).isEmpty();
        assertThat(drafts(owner, project)).isZero();

        assertThat(papers.existsById(keptPaper)).isTrue();
        assertThat(papers.existsById(someoneElsesPaper)).isTrue();
        assertThat(drafts(owner, otherProject)).isEqualTo(1);
        assertThat(files.read(keptDraftFile)).isPresent();
        assertThat(drafts(someoneElse, project)).isEqualTo(1);
        assertThat(files.read(someoneElsesDraftFile)).isPresent();
    }

    // safe to repeat, so User Management can retry a delete that failed halfway
    @Test
    void clearingAProjectWithNothingInItIsStillNoContent() throws Exception {
        clear(project, owner).andExpect(status().isNoContent());
    }

    // a double-clicked delete in the frontend reaches here twice
    @Test
    void twoClearsOfTheSameProjectAtOnceAreBothNoContent() throws Exception {
        savePaper(owner, project);
        saveDraft(owner, project);
        Callable<Integer> attempt = () -> clear(project, owner).andReturn().getResponse().getStatus();

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            statuses = pool.invokeAll(List.of(attempt, attempt)).stream().map(Future::resultNow).toList();
        }

        assertThat(statuses).containsExactly(204, 204);
    }

    private ResultActions clear(UUID projectId, UUID ownerId) throws Exception {
        return mvc.perform(delete("/internal/projects/{id}", projectId).param("owner_id", ownerId.toString())
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    // a fresh DOI each time, since an owner can't track one DOI twice
    private UUID savePaper(UUID paperOwner, UUID paperProject) {
        Paper paper = new Paper(paperOwner);
        paper.setDoi("10.1000/" + UUID.randomUUID());
        paper.setProjectId(paperProject);
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

    private String saveDraft(UUID draftOwner, UUID draftProject) {
        String file = files.save(PDF);
        jdbc.update("insert into drafts (id, owner_id, project_id, file_key, filename, uploaded_at) values (?, ?, ?, ?, 'draft.pdf', current_timestamp)",
                UUID.randomUUID(), draftOwner, draftProject, file);
        return file;
    }

    private int count(String table, UUID paper) {
        return jdbc.queryForObject("select count(*) from " + table + " where paper_id = ?", Integer.class, paper);
    }

    private int drafts(UUID draftOwner, UUID draftProject) {
        return jdbc.queryForObject("select count(*) from drafts where owner_id = ? and project_id = ?",
                Integer.class, draftOwner, draftProject);
    }
}
