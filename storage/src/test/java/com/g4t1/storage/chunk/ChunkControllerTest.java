package com.g4t1.storage.chunk;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PUT /internal/chunks on H2, where the embedding column is a real array (the vendor/h2 migration).
// ChunkPostgresIT covers the real vector(1536) column.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PaperRepository papers;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID owner = UUID.randomUUID();
    private UUID paper;
    private long alert;
    private long report;
    private long document;

    @BeforeEach
    void setUp() {
        paper = savePaper(owner);
        jdbc.update("""
                insert into snapshots (paper_id, doi, fetched_at, source_status)
                values (?, '10.1016/s0140-6736(20)31180-6', current_timestamp, '{}')""", paper);
        long snapshot = jdbc.queryForObject("select snapshot_id from snapshots where paper_id = ?", Long.class, paper);
        jdbc.update("insert into reports (paper_id, created_at) values (?, current_timestamp)", paper);
        report = jdbc.queryForObject("select id from reports where paper_id = ?", Long.class, paper);
        jdbc.update("""
                insert into alerts (paper_id, change_type, change_key, alert_severity, description, recommendation,
                                    detected_at, snapshot_id, previous_snapshot_id, created_at, report_id)
                values (?, 'retraction', 'openalex:is_retracted', 'high', 'd', 'r', current_timestamp, ?, ?, current_timestamp, ?)
                """, paper, snapshot, snapshot, report);
        alert = jdbc.queryForObject("select id from alerts where paper_id = ?", Long.class, paper);
        jdbc.update("""
                insert into report_documents (report_id, kind, doi, crossref_status, text_status, pdf_status, created_at)
                values (?, 'notice', '10.1016/s0140-6736(20)31324-6', 'ok', 'none', 'none', current_timestamp)""", report);
        document = jdbc.queryForObject("select id from report_documents where report_id = ?", Long.class, report);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from chunks");
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        jdbc.update("delete from snapshots");
        papers.deleteAll();
    }

    @Test
    void replacesSourceChunks() throws Exception {
        store(body("alert", alert, chunk("first", null), chunk("second", null))).andExpect(status().isNoContent());
        store(body("alert", alert, chunk("only", null))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("select content from chunks where alert_id = ?", String.class, alert))
                .containsExactly("only");
    }

    @Test
    void storesEachChunkWithItsIndexPageAndThePaperOfItsSource() throws Exception {
        store(body("document", document, chunk("page one", 1), chunk("page two", 2))).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select source_type, document_id, paper_id, chunk_index, content, page, embed_model
                from chunks order by chunk_index""");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("source_type", "document").containsEntry("document_id", document)
                .containsEntry("paper_id", paper).containsEntry("chunk_index", 0).containsEntry("content", "page one")
                .containsEntry("page", 1).containsEntry("embed_model", "gemini-embedding-001");
        assertThat(rows.get(1)).containsEntry("chunk_index", 1).containsEntry("page", 2);
    }

    @Test
    void storesReportChunks() throws Exception {
        store(body("report", report, chunk("the report", null))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select paper_id from chunks where report_id = ?", UUID.class, report)).isEqualTo(paper);
    }

    @Test
    void emptyChunksDeletes() throws Exception {
        store(body("alert", alert, chunk("first", null))).andExpect(status().isNoContent());

        store(body("alert", alert)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from chunks", Integer.class)).isZero();
    }

    @Test
    void missingFieldIsBadRequest() throws Exception {
        for (String field : List.of("source_type", "source_id", "embed_model", "chunks")) {
            Map<String, Object> body = body("alert", alert, chunk("text", null));
            body.remove(field);
            store(body).andExpect(status().isBadRequest());
        }
        Map<String, Object> noEmbedding = chunk("text", null);
        noEmbedding.remove("embedding");
        store(body("alert", alert, noEmbedding)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownSourceTypeIsBadRequest() throws Exception {
        store(body("draft", alert, chunk("text", null))).andExpect(status().isBadRequest());
    }

    @Test
    void unparseableSourceIdIsBadRequest() throws Exception {
        Map<String, Object> notAnId = body("alert", alert, chunk("text", null));
        notAnId.put("source_id", "abc");
        store(notAnId).andExpect(status().isBadRequest());

        Map<String, Object> notAUuid = body("profile", alert, chunk("text", null));
        notAUuid.put("source_id", "42");
        store(notAUuid).andExpect(status().isBadRequest());
    }

    @Test
    void embeddingNotOf1536NumbersIsBadRequest() throws Exception {
        Map<String, Object> tooShort = chunk("text", null);
        tooShort.put("embedding", embedding(1535));
        store(body("alert", alert, tooShort)).andExpect(status().isBadRequest());

        Map<String, Object> tooLong = chunk("text", null);
        tooLong.put("embedding", embedding(1537));
        store(body("alert", alert, tooLong)).andExpect(status().isBadRequest());

        Map<String, Object> withNull = chunk("text", null);
        List<Object> values = new ArrayList<>(embedding(1536));
        values.set(3, null);
        withNull.put("embedding", values);
        store(body("alert", alert, withNull)).andExpect(status().isBadRequest());
    }

    @Test
    void emptyContentIsBadRequest() throws Exception {
        store(body("alert", alert, chunk("", null))).andExpect(status().isBadRequest());
    }

    @Test
    void pageBelowOneIsBadRequest() throws Exception {
        store(body("document", document, chunk("text", 0))).andExpect(status().isBadRequest());
    }

    @Test
    void missingSourceIsNotFound() throws Exception {
        store(body("alert", 999_999, chunk("text", null))).andExpect(status().isNotFound());
        store(body("report", 999_999, chunk("text", null))).andExpect(status().isNotFound());
        store(body("document", 999_999, chunk("text", null))).andExpect(status().isNotFound());
    }

    @Test
    void userTokenForbidden() throws Exception {
        mvc.perform(put("/internal/chunks")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("alert", alert, chunk("text", null)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletedWithPaper() throws Exception {
        store(body("alert", alert, chunk("alert", null))).andExpect(status().isNoContent());
        store(body("report", report, chunk("report", null))).andExpect(status().isNoContent());
        store(body("document", document, chunk("document", 1))).andExpect(status().isNoContent());

        mvc.perform(delete("/papers/{id}", paper).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from chunks", Integer.class)).isZero();
    }

    @Test
    void replacesProfileChunks() throws Exception {
        store(profile(owner, chunk("expertise: a", null), chunk("interests: b", null), chunk("fields: c", null)))
                .andExpect(status().isNoContent());
        store(profile(owner, chunk("expertise: x", null), chunk("interests: y", null))).andExpect(status().isNoContent());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select content, paper_id from chunks where user_id = ? order by chunk_index", owner);
        assertThat(rows).extracting(row -> row.get("content")).containsExactly("expertise: x", "interests: y");
        assertThat(rows).extracting(row -> row.get("paper_id")).containsOnlyNulls();
    }

    @Test
    void deletedWithUser() throws Exception {
        UUID someoneElse = UUID.randomUUID();
        store(profile(owner, chunk("expertise: a", null))).andExpect(status().isNoContent());
        store(body("alert", alert, chunk("alert", null))).andExpect(status().isNoContent());
        store(profile(someoneElse, chunk("expertise: b", null))).andExpect(status().isNoContent());

        mvc.perform(delete("/internal/users/{id}", owner).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from chunks where user_id = ?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chunks where alert_id = ?", Integer.class, alert)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chunks where user_id = ?", Integer.class, someoneElse))
                .isEqualTo(1);
    }

    private ResultActions store(Map<String, Object> body) throws Exception {
        return mvc.perform(put("/internal/chunks")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @SafeVarargs
    private static Map<String, Object> body(String sourceType, long sourceId, Map<String, Object>... chunks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_type", sourceType);
        body.put("source_id", String.valueOf(sourceId));
        body.put("embed_model", "gemini-embedding-001");
        body.put("chunks", List.of(chunks));
        return body;
    }

    @SafeVarargs
    private static Map<String, Object> profile(UUID user, Map<String, Object>... chunks) {
        Map<String, Object> body = body("profile", 0, chunks);
        body.put("source_id", user.toString());
        return body;
    }

    private static Map<String, Object> chunk(String content, Integer page) {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("content", content);
        chunk.put("page", page);
        chunk.put("embedding", embedding(1536));
        return chunk;
    }

    static List<Object> embedding(int size) {
        return new ArrayList<>(Collections.nCopies(size, 0.01));
    }

    // a fresh DOI each time, since an owner can't track one DOI twice
    private UUID savePaper(UUID owner) {
        Paper paper = new Paper(owner);
        paper.setDoi("10.1000/" + UUID.randomUUID());
        return papers.save(paper).getId();
    }
}
