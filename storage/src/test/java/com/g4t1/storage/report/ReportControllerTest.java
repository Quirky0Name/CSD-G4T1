package com.g4t1.storage.report;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// context_chunks: sent with impact's evaluation, stored as sent, and returned in the researcher's report list
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportControllerTest {

    private static final List<Map<String, Object>> CONTEXT_CHUNKS = List.of(
            chunk("document", "7", 3),
            chunk("alert", "42", null));

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
    private long report;

    @BeforeEach
    void setUp() {
        Paper saved = new Paper(owner);
        saved.setDoi("10.1000/" + UUID.randomUUID());
        paper = papers.save(saved).getId();
        jdbc.update("insert into reports (paper_id, status, created_at) values (?, 'investigated', current_timestamp)", paper);
        report = jdbc.queryForObject("select id from reports where paper_id = ?", Long.class, paper);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        papers.deleteAll();
    }

    @Test
    void contextChunksRoundTrip() throws Exception {
        Map<String, Object> evaluation = evaluation();
        evaluation.put("context_chunks", CONTEXT_CHUNKS);

        record(evaluation);

        JsonNode listed = listedReport();
        assertThat(listed.get("context_chunks").isArray()).isTrue();
        assertThat(listed.get("context_chunks")).isEqualTo(json.valueToTree(CONTEXT_CHUNKS));
    }

    @Test
    void contextChunksAbsent() throws Exception {
        record(evaluation());

        JsonNode listed = listedReport();
        assertThat(listed.has("context_chunks")).isTrue();
        assertThat(listed.get("context_chunks").isNull()).isTrue();
    }

    @Test
    void contextChunksNullIsStoredAsNull() throws Exception {
        Map<String, Object> evaluation = evaluation();
        evaluation.put("context_chunks", null);

        record(evaluation);

        assertThat(listedReport().get("context_chunks").isNull()).isTrue();
    }

    @Test
    void anEmptyListIsKept() throws Exception {
        Map<String, Object> evaluation = evaluation();
        evaluation.put("context_chunks", List.of());

        record(evaluation);

        JsonNode chunks = listedReport().get("context_chunks");
        assertThat(chunks.isArray()).isTrue();
        assertThat(chunks).isEmpty();
    }

    private void record(Map<String, Object> evaluation) throws Exception {
        mvc.perform(put("/internal/reports/{id}/evaluation", report)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(evaluation)))
                .andExpect(status().isOk());
    }

    private JsonNode listedReport() throws Exception {
        String body = mvc.perform(get("/papers/{id}/reports", paper)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode reports = json.readTree(body);
        JsonNode list = reports.isArray() ? reports : reports.get("reports");
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    private static Map<String, Object> evaluation() {
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("change_summary", "Table 2 was corrected.");
        evaluation.put("change_severity", "high");
        evaluation.put("impact_level", "medium");
        evaluation.put("evaluation", "Your Discussion relies on Table 2.");
        evaluation.put("recommendation", "Recheck Table 2.");
        evaluation.put("assessment", Map.of("draft", "read"));
        return evaluation;
    }

    private static Map<String, Object> chunk(String sourceType, String sourceId, Integer page) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("source_type", sourceType);
        chunk.put("source_id", sourceId);
        chunk.put("page", page);
        return chunk;
    }
}
