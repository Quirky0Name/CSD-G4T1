package com.g4t1.storage.chunk;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /internal/chunks/search on real Postgres with pgvector: ranking, scoping and what each result carries
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkSearchPostgresIT extends PgvectorContainer {

    private static final Instant DETECTED = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant EVALUATED = Instant.parse("2026-09-02T08:00:00Z");
    private static final Instant FETCHED = Instant.parse("2026-09-03T08:00:00Z");
    private static final Instant FEEDBACK = Instant.parse("2026-09-04T08:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaperRepository papers;

    private final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        jdbc.update("delete from chunks");
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        jdbc.update("delete from snapshots");
        papers.deleteAll();
    }

    @Test
    void nearestFirst() throws Exception {
        Source source = history();
        // stored farthest first, so the order back can only come from the distance
        store("document", source.document(), List.of(
                chunk("orthogonal", 2, axis(1)),
                chunk("same direction", 3, axis(0)),
                chunk("halfway", 4, sum(axis(0), axis(1)))));

        JsonNode results = search(source.paper(), "document", axis(0), 3);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("content").asString()).isEqualTo("same direction");
        assertThat(results.get(1).get("content").asString()).isEqualTo("halfway");
        assertThat(results.get(2).get("content").asString()).isEqualTo("orthogonal");
        assertThat(results.get(0).get("score").asDouble()).isCloseTo(1.0, within(1e-6));
        assertThat(results.get(1).get("score").asDouble()).isCloseTo(Math.sqrt(0.5), within(1e-6));
        assertThat(results.get(2).get("score").asDouble()).isCloseTo(0.0, within(1e-6));
        assertThat(results.get(0).get("chunk_index").asInt()).isEqualTo(1);
        assertThat(results.get(0).get("page").asInt()).isEqualTo(3);

        assertThat(search(source.paper(), "document", axis(0), 2)).hasSize(2);
    }

    @Test
    void paperScoped() throws Exception {
        Source mine = history();
        Source other = history();
        store("alert", mine.alert(), List.of(chunk("mine", null, axis(0))));
        store("alert", other.alert(), List.of(chunk("other", null, axis(0))));

        JsonNode results = search(mine.paper(), "alert", axis(0), 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("content").asString()).isEqualTo("mine");
    }

    @Test
    void sourceTypeFilter() throws Exception {
        Source source = history();
        store("alert", source.alert(), List.of(chunk("alert", null, axis(0))));
        store("report", source.report(), List.of(chunk("report", null, axis(0))));
        store("document", source.document(), List.of(chunk("document", 1, axis(0))));

        JsonNode results = search(source.paper(), "report", axis(0), 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("source_type").asString()).isEqualTo("report");
        assertThat(results.get(0).get("content").asString()).isEqualTo("report");
    }

    @Test
    void feedbackSpansEveryPaperOfTheOwnerAndNoOneElses() throws Exception {
        Source first = history();
        Source second = history(first.owner(), "Second paper");
        Source someoneElse = history();
        store("feedback", first.report(), List.of(chunk("feedback on first", null, sum(axis(0), axis(1)))));
        store("feedback", second.report(), List.of(chunk("feedback on second", null, axis(0))));
        store("feedback", someoneElse.report(), List.of(chunk("someone else's", null, axis(0))));
        store("report", first.report(), List.of(chunk("a report chunk", null, axis(0))));

        JsonNode results = search(first.paper(), "feedback", axis(0), 20);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("content").asString()).isEqualTo("feedback on second");
        assertThat(results.get(0).get("source_type").asString()).isEqualTo("feedback");
        assertThat(results.get(0).get("source_id").asString()).isEqualTo(String.valueOf(second.report()));
        assertThat(results.get(0).get("paper_id").asString()).isEqualTo(second.paper().toString());
        assertThat(results.get(0).get("paper_title").asString()).isEqualTo("Second paper");
        assertThat(results.get(0).get("report_id").asLong()).isEqualTo(second.report());
        assertThat(Instant.parse(results.get(0).get("source_date").asString())).isEqualTo(FEEDBACK);
        assertThat(results.get(1).get("content").asString()).isEqualTo("feedback on first");
        assertThat(results.get(1).get("paper_id").asString()).isEqualTo(first.paper().toString());
        assertThat(results.get(1).get("paper_title").asString()).isEqualTo("Tracked paper");
        assertThat(results.get(1).get("report_id").asLong()).isEqualTo(first.report());
    }

    @Test
    void aChunkOfDeletedFeedbackIsLeftOutAndTakesNoSlot() throws Exception {
        Source deleted = history();
        Source kept = history(deleted.owner(), "Second paper");
        store("feedback", deleted.report(), List.of(chunk("deleted feedback", null, axis(0))));
        store("feedback", kept.report(), List.of(chunk("kept feedback", null, sum(axis(0), axis(1)))));
        jdbc.update("""
                update reports set feedback_verdict = null, feedback_note = null, feedback_updated_at = null
                where id = ?""", deleted.report());

        JsonNode results = search(deleted.paper(), "feedback", axis(0), 1);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("content").asString()).isEqualTo("kept feedback");
    }

    @Test
    void aDocumentSearchStaysOnThePaperAndNamesIt() throws Exception {
        Source first = history();
        Source second = history(first.owner(), "Second paper");
        store("document", first.document(), List.of(chunk("first's document", 1, axis(0))));
        store("document", second.document(), List.of(chunk("second's document", 1, axis(0))));

        JsonNode results = search(first.paper(), "document", axis(0), 20);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("content").asString()).isEqualTo("first's document");
        assertThat(results.get(0).get("paper_id").asString()).isEqualTo(first.paper().toString());
        assertThat(results.get(0).get("paper_title").asString()).isEqualTo("Tracked paper");
    }

    @Test
    void reportIdAndDate() throws Exception {
        Source source = history();
        store("alert", source.alert(), List.of(chunk("alert", null, axis(0))));
        store("report", source.report(), List.of(chunk("report", null, axis(0))));
        store("document", source.document(), List.of(chunk("document", 1, axis(0))));
        store("feedback", source.report(), List.of(chunk("feedback", null, axis(0))));

        JsonNode alert = search(source.paper(), "alert", axis(0), 1).get(0);
        assertThat(alert.get("source_id").asString()).isEqualTo(String.valueOf(source.alert()));
        assertThat(alert.get("report_id").asLong()).isEqualTo(source.report());
        assertThat(Instant.parse(alert.get("source_date").asString())).isEqualTo(DETECTED);

        JsonNode report = search(source.paper(), "report", axis(0), 1).get(0);
        assertThat(report.get("source_id").asString()).isEqualTo(String.valueOf(source.report()));
        assertThat(report.get("report_id").asLong()).isEqualTo(source.report());
        assertThat(Instant.parse(report.get("source_date").asString())).isEqualTo(EVALUATED);

        JsonNode document = search(source.paper(), "document", axis(0), 1).get(0);
        assertThat(document.get("source_id").asString()).isEqualTo(String.valueOf(source.document()));
        assertThat(document.get("report_id").asLong()).isEqualTo(source.report());
        assertThat(Instant.parse(document.get("source_date").asString())).isEqualTo(FETCHED);

        JsonNode feedback = search(source.paper(), "feedback", axis(0), 1).get(0);
        assertThat(feedback.get("source_id").asString()).isEqualTo(String.valueOf(source.report()));
        assertThat(feedback.get("report_id").asLong()).isEqualTo(source.report());
        assertThat(feedback.get("page").isNull()).isTrue();
        assertThat(Instant.parse(feedback.get("source_date").asString())).isEqualTo(FEEDBACK);
    }

    private record Source(UUID owner, UUID paper, long alert, long report, long document) {
    }

    private Source history() {
        return history(UUID.randomUUID(), "Tracked paper");
    }

    // a paper with one assessed report that has feedback, its alert and its document, each with a known date
    private Source history(UUID owner, String title) {
        Paper paper = new Paper(owner);
        paper.setDoi("10.1000/" + UUID.randomUUID());
        paper.setTitle(title);
        UUID paperId = papers.save(paper).getId();
        jdbc.update("""
                insert into snapshots (paper_id, doi, fetched_at, source_status)
                values (?, '10.1016/s0140-6736(20)31180-6', current_timestamp, '{}')""", paperId);
        long snapshot = jdbc.queryForObject("select snapshot_id from snapshots where paper_id = ?", Long.class, paperId);
        jdbc.update("""
                insert into reports (paper_id, status, created_at, evaluated_at, feedback_verdict, feedback_updated_at)
                values (?, 'assessed', current_timestamp, ?, 'overstated', ?)""", paperId, Timestamp.from(EVALUATED),
                Timestamp.from(FEEDBACK));
        long report = jdbc.queryForObject("select id from reports where paper_id = ?", Long.class, paperId);
        jdbc.update("""
                insert into alerts (paper_id, change_type, change_key, alert_severity, description, recommendation,
                                    detected_at, snapshot_id, previous_snapshot_id, created_at, report_id)
                values (?, 'retraction', 'openalex:is_retracted', 'high', 'd', 'r', ?, ?, ?, current_timestamp, ?)
                """, paperId, Timestamp.from(DETECTED), snapshot, snapshot, report);
        long alert = jdbc.queryForObject("select id from alerts where paper_id = ?", Long.class, paperId);
        jdbc.update("""
                insert into report_documents (report_id, kind, doi, crossref_status, text_status, pdf_status, created_at)
                values (?, 'notice', '10.1016/s0140-6736(20)31324-6', 'ok', 'none', 'none', ?)""",
                report, Timestamp.from(FETCHED));
        long document = jdbc.queryForObject("select id from report_documents where report_id = ?", Long.class, report);
        return new Source(owner, paperId, alert, report, document);
    }

    private void store(String sourceType, Object sourceId, List<Map<String, Object>> chunks) throws Exception {
        mvc.perform(put("/internal/chunks")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "source_type", sourceType,
                                "source_id", sourceId.toString(),
                                "embed_model", "gemini-embedding-001",
                                "chunks", chunks))))
                .andExpect(status().isNoContent());
    }

    private JsonNode search(UUID paper, String sourceType, List<Double> embedding, int limit) throws Exception {
        String body = mvc.perform(post("/internal/chunks/search")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "paper_id", paper.toString(),
                                "embedding", embedding,
                                "source_type", sourceType,
                                "limit", limit))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static Map<String, Object> chunk(String content, Integer page, List<Double> embedding) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("content", content);
        chunk.put("page", page);
        chunk.put("embedding", embedding);
        return chunk;
    }

    // a unit vector along one dimension
    private static List<Double> axis(int dimension) {
        List<Double> vector = new ArrayList<>(Collections.nCopies(ChunkRepository.DIMENSIONS, 0.0));
        vector.set(dimension, 1.0);
        return vector;
    }

    private static List<Double> sum(List<Double> a, List<Double> b) {
        List<Double> vector = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            vector.add(a.get(i) + b.get(i));
        }
        return vector;
    }
}
