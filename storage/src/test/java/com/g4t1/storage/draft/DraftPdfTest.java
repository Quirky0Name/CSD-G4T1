package com.g4t1.storage.draft;

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

// GET /draft/pdf: the project's current draft, for its owner only; no project_id is the default project
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftPdfTest {

    private static final byte[] DEFAULT_PDF = "%PDF-1.7 default draft".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PROJECT_PDF = "%PDF-1.7 project draft".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    DraftRepository drafts;

    @Autowired
    LocalFileStore files;

    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    @BeforeEach
    void storeDrafts() {
        drafts.save(new Draft(owner, null, files.save(DEFAULT_PDF), "default.pdf"));
        drafts.save(new Draft(owner, project, files.save(PROJECT_PDF), "project.pdf"));
    }

    @Test
    void theOwnerGetsTheDefaultProjectsDraftWithoutProjectId() throws Exception {
        mvc.perform(get("/draft/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(DEFAULT_PDF));
    }

    @Test
    void theOwnerGetsAProjectsDraftWithProjectId() throws Exception {
        mvc.perform(get("/draft/pdf").param("project_id", project.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PROJECT_PDF));
    }

    @Test
    void anotherUsersProjectIsNotFound() throws Exception {
        mvc.perform(get("/draft/pdf").param("project_id", project.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserWithNoDefaultDraftIsNotFound() throws Exception {
        mvc.perform(get("/draft/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aProjectWithNoDraftIsNotFound() throws Exception {
        mvc.perform(get("/draft/pdf").param("project_id", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aServiceTokenIsForbidden() throws Exception {
        mvc.perform(get("/draft/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/draft/pdf")).andExpect(status().isUnauthorized());
    }
}
