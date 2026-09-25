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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalPapersTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    @BeforeEach
    void clean() {
        papers.deleteAll();
    }

    @Test
    void serviceTokenListsEveryUsersPapers() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Paper lancet = save(alice, "10.1016/s0140-6736(20)31180-6", "0140-6736");
        save(bob, "10.1000/xyz123", null);

        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].owner_id", containsInAnyOrder(alice.toString(), bob.toString())))
                .andExpect(jsonPath("$[?(@.id == '" + lancet.getId() + "')].doi")
                        .value("10.1016/s0140-6736(20)31180-6"))
                .andExpect(jsonPath("$[?(@.id == '" + lancet.getId() + "')].issn").value("0140-6736"));
    }

    @Test
    void paperWithoutDoiIsStillListed() throws Exception {
        save(UUID.randomUUID(), null, null);

        // Updating skips and logs these itself, so storage doesn't filter them out
        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].doi").value(nullValue()));
    }

    @Test
    void userTokensAndMissingTokensAreRefused() throws Exception {
        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/papers"))
                .andExpect(status().isUnauthorized());
    }

    private Paper save(UUID owner, String doi, String issn) {
        Paper paper = new Paper(owner);
        paper.setDoi(doi);
        paper.setIssn(issn);
        return papers.save(paper);
    }
}
