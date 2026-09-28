package com.g4t1.storage.dev;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Without the dev profile none of the test tooling exists, so a normal run is unchanged
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevToolsOffTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private final String token = TestTokens.user(UUID.randomUUID());

    @Test
    void theDevEndpointsDoNotExist() throws Exception {
        UUID paper = UUID.randomUUID();
        mvc.perform(post("/dev/papers/{id}/undo-change", paper).param("change", "retraction")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/dev/papers/{id}/history", paper).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    @Test
    void swaggerIsOffAndNotOpenedUp() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound());
    }
}
