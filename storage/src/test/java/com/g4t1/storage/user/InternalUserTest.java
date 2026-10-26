package com.g4t1.storage.user;

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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DELETE /internal/users/{id}: User Management clearing out everything of an account being deleted
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalUserTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private final UUID user = UUID.randomUUID();

    // safe to repeat, so User Management can retry a delete that failed halfway
    @Test
    void clearingAUserWithNothingStoredIsStillNoContent() throws Exception {
        clear(user).andExpect(status().isNoContent());
    }

    private ResultActions clear(UUID userId) throws Exception {
        return mvc.perform(delete("/internal/users/{id}", userId)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }
}
