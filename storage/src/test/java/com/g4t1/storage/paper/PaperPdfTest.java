package com.g4t1.storage.paper;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.file.LocalFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /papers/{id}/pdf: the tracked paper's stored PDF, for its owner only
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperPdfTest {

    private static final byte[] PDF = "%PDF-1.7 the stored paper".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    LocalFileStore files;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;

    @BeforeEach
    void storePaper() {
        Paper paper = new Paper(owner);
        paper.setFileKey(files.save(PDF));
        paperId = papers.save(paper).getId();
    }

    @Test
    void theOwnerGetsTheStoredPdf() throws Exception {
        mvc.perform(get("/papers/" + paperId + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF));
    }

    @Test
    void anotherUsersPaperIsNotFound() throws Exception {
        mvc.perform(get("/papers/" + paperId + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingPaperIsNotFound() throws Exception {
        mvc.perform(get("/papers/" + UUID.randomUUID() + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPaperWithNoStoredPdfIsNotFound() throws Exception {
        UUID bare = papers.save(new Paper(owner)).getId();

        mvc.perform(get("/papers/" + bare + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aServiceTokenIsForbidden() throws Exception {
        mvc.perform(get("/papers/" + paperId + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/papers/" + paperId + "/pdf")).andExpect(status().isUnauthorized());
    }
}
