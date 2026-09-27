package com.g4t1.storage.paper;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperListTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void clean() {
        papers.deleteAll();
    }

    @Test
    void listsOnlyTheCallersPapersNewestFirst() throws Exception {
        save(user, "10.1000/older", null);
        UUID folder = UUID.randomUUID();
        save(user, "10.1000/newer", folder);
        save(UUID.randomUUID(), "10.1000/someone-else", null);

        mvc.perform(get("/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers[*].doi").value(contains("10.1000/newer", "10.1000/older")))
                .andExpect(jsonPath("$.papers[0].folder_id").value(folder.toString()))
                .andExpect(jsonPath("$.papers[0].title").value("A paper"));
    }

    @Test
    void userWithNoPapersGetsAnEmptyList() throws Exception {
        mvc.perform(get("/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers").isEmpty());
    }

    @Test
    void serviceTokensAndMissingTokensAreRefused() throws Exception {
        mvc.perform(get("/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/papers"))
                .andExpect(status().isUnauthorized());
    }

    private void save(UUID owner, String doi, UUID folder) throws InterruptedException {
        Paper paper = new Paper(owner);
        paper.setDoi(doi);
        paper.setTitle("A paper");
        paper.setFolderId(folder);
        papers.save(paper);
        // created_at is set on save; keep the two rows' timestamps apart so the order is certain
        Thread.sleep(5);
    }
}
