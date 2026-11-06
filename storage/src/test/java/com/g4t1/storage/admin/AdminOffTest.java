package com.g4t1.storage.admin;

import com.g4t1.storage.TestTokens;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Dev mode is off unless asked for: the admin endpoints aren't there, whoever asks. The paper is real,
// since the endpoints answer an unknown paper with a 404 of their own.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOffTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    SnapshotRepository snapshots;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    JsonMapper json;

    private UUID paperId;

    @BeforeEach
    void setUp() {
        Paper paper = new Paper(UUID.randomUUID());
        paper.setDoi("10.1016/s0140-6736(20)31180-6");
        paperId = papers.save(paper).getId();
        snapshotService.store(paperId, new SnapshotRequest(
                "10.1016/s0140-6736(20)31180-6", Instant.parse("2026-09-24T12:00:00Z"), null, null, null,
                true, null, null, null, null, null, null, null, null, null,
                json.readTree("{\"crossref\": \"ok\", \"openalex\": \"ok\", \"openalex_authors\": \"ok\"}")));
    }

    @AfterEach
    void tearDown() {
        snapshots.deleteAll();
        papers.deleteAll();
    }

    @Test
    void editingASnapshotIsNotFoundAndStoresNothing() throws Exception {
        mvc.perform(post("/admin/papers/{id}/snapshots", paperId)
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_retracted\": false}"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/internal/papers/{id}/snapshots", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(jsonPath("$.snapshots.length()").value(1));
    }

    private static String admin() {
        return TestTokens.admin(UUID.randomUUID());
    }
}
