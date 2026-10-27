package com.g4t1.storage.dev;

import com.g4t1.storage.TestTokens;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DEV_TOOLS=false, for a server where the demo tools shouldn't be reachable
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "storage.dev-tools=false")
class DevToolsOffTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private final UUID user = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        papers.deleteAll();
    }

    @Test
    void theDemoToolsAreGone() throws Exception {
        Paper paper = new Paper(user);
        paper.setDoi("10.1000/" + UUID.randomUUID());
        UUID paperId = papers.save(paper).getId();
        String token = TestTokens.user(user);

        mvc.perform(post("/dev/papers/{id}/undo-change", paperId).param("change", "retraction")
                .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound());
        mvc.perform(delete("/dev/papers/{id}/history", paperId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/dev/papers/{id}", paperId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());

        assertThat(papers.existsById(paperId)).isTrue();
    }

    @Test
    void swaggerIsGone() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
