package com.g4t1.storage.chunk;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The chunks migration on real Postgres with pgvector, and a vector stored through PUT /internal/chunks
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChunkPostgresIT extends PgvectorContainer {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PaperRepository papers;

    @Test
    void migrationAppliesAndAStoredVectorReadsBack() throws Exception {
        assertThat(jdbc.queryForObject("select format_type(atttypid, atttypmod) from pg_attribute"
                + " where attrelid = 'chunks'::regclass and attname = 'embedding'", String.class)).isEqualTo("vector(1536)");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'idx_chunks_embedding'",
                String.class)).contains("hnsw").contains("vector_cosine_ops");

        UUID user = UUID.randomUUID();
        List<Double> embedding = new ArrayList<>();
        for (int i = 0; i < ChunkRepository.DIMENSIONS; i++) {
            embedding.add((i % 7 - 3) / 100.0);
        }
        mvc.perform(put("/internal/chunks")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "source_type", "profile",
                                "source_id", user.toString(),
                                "embed_model", "gemini-embedding-001",
                                "chunks", List.of(Map.of("content", "expertise: epidemiology", "embedding", embedding))))))
                .andExpect(status().isNoContent());

        String stored = jdbc.queryForObject("select embedding::text from chunks where user_id = ?", String.class, user);
        double[] values = Arrays.stream(stored.substring(1, stored.length() - 1).split(","))
                .mapToDouble(Double::parseDouble).toArray();
        assertThat(values).hasSize(ChunkRepository.DIMENSIONS);
        for (int i = 0; i < values.length; i++) {
            assertThat(values[i]).isCloseTo(embedding.get(i), within(1e-6));
        }
        jdbc.update("delete from chunks where user_id = ?", user);
    }

    @Test
    void aPapersChunksCascadeWithItOnPostgres() throws Exception {
        Paper paper = new Paper(UUID.randomUUID());
        paper.setDoi("10.1000/" + UUID.randomUUID());
        UUID paperId = papers.save(paper).getId();
        jdbc.update("insert into reports (paper_id, created_at) values (?, current_timestamp)", paperId);
        long report = jdbc.queryForObject("select id from reports where paper_id = ?", Long.class, paperId);
        mvc.perform(put("/internal/chunks")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "source_type", "report",
                                "source_id", String.valueOf(report),
                                "embed_model", "gemini-embedding-001",
                                "chunks", List.of(Map.of("content", "the report",
                                        "embedding", ChunkControllerTest.embedding(ChunkRepository.DIMENSIONS)))))))
                .andExpect(status().isNoContent());

        jdbc.update("delete from papers where id = ?", paperId);

        assertThat(jdbc.queryForObject("select count(*) from chunks where paper_id = ?", Integer.class, paperId)).isZero();
    }
}
