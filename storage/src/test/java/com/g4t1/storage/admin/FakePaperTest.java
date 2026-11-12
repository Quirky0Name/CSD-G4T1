package com.g4t1.storage.admin;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /admin/users/{ownerId}/fake-paper: the example's paper.pdf as a new paper of a researcher
@SpringBootTest(properties = "storage.dev-mode=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FakePaperTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    LocalFileStore files;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OpenAccessPdfClient openAccess;

    @MockitoBean
    MetadataClient metadata;

    private final UUID owner = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        papers.deleteAll();
    }

    @Test
    void itIsTheExamplePaperInTheOwnersDefaultProjectWithNoDoi() throws Exception {
        create()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.owner_id").value(owner.toString()))
                .andExpect(jsonPath("$.project_id", nullValue()))
                .andExpect(jsonPath("$.title").value("Dose-response of iron supplementation on fatigue"))
                .andExpect(jsonPath("$.journal").value("J. Fake Nutrition"))
                .andExpect(jsonPath("$.publication_year").value(2019))
                .andExpect(jsonPath("$.doi", nullValue()))
                .andExpect(jsonPath("$.openalex_id", nullValue()))
                .andExpect(jsonPath("$.issn", nullValue()))
                .andExpect(jsonPath("$.file_available").value(true))
                .andExpect(jsonPath("$.latest_snapshot", nullValue()));
        // nothing is looked up for a made-up paper
        verifyNoInteractions(metadata, openAccess);
    }

    @Test
    void itsStoredFileIsTheExamplePaperPdf() throws Exception {
        UUID id = createdId();

        Paper paper = papers.findById(id).orElseThrow();
        byte[] expected = examplePaper();
        assertThat(files.read(paper.getFileKey())).hasValueSatisfying(
                bytes -> assertThat(bytes).isEqualTo(expected));
        mvc.perform(get("/internal/papers/{id}/pdf", id).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(examplePaper()));
    }

    @Test
    void theOwnerAndTheAdminSeeIt() throws Exception {
        UUID id = createdId();

        mvc.perform(get("/papers").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.papers", hasSize(1)))
                .andExpect(jsonPath("$.papers[0].id").value(id.toString()));
        admin(get("/admin/users/{ownerId}/papers", owner))
                .andExpect(jsonPath("$.papers", hasSize(1)))
                .andExpect(jsonPath("$.papers[0].id").value(id.toString()));
    }

    @Test
    void eachCallAddsAnotherPaper() throws Exception {
        UUID first = createdId();
        UUID second = createdId();

        assertThat(first).isNotEqualTo(second);
        assertThat(papers.findByOwnerIdAndProjectId(owner, null)).hasSize(2);
    }

    @Test
    void aFakeReportCanGoOnIt() throws Exception {
        UUID id = createdId();

        admin(post("/admin/papers/{id}/fake-report", id))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paper_id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("assessed"));
    }

    @Test
    void theOwnerCanDeleteItAndItsFileGoes() throws Exception {
        UUID id = createdId();
        String key = papers.findById(id).orElseThrow().getFileKey();

        mvc.perform(delete("/papers/{id}", id).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNoContent());

        assertThat(papers.findById(id)).isEmpty();
        assertThat(files.read(key)).isEmpty();
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        admin(post("/admin/users/{ownerId}/fake-paper", "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/admin/users/{ownerId}/fake-paper", owner)).andExpect(status().isUnauthorized());
    }

    @Test
    void userAndServiceTokensAreForbiddenAndStoreNothing() throws Exception {
        mvc.perform(post("/admin/users/{ownerId}/fake-paper", owner)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{ownerId}/fake-paper", owner)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());

        assertThat(papers.count()).isZero();
    }

    private ResultActions create() throws Exception {
        return admin(post("/admin/users/{ownerId}/fake-paper", owner));
    }

    private UUID createdId() throws Exception {
        String body = create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder call)
            throws Exception {
        return mvc.perform(call.header(HttpHeaders.AUTHORIZATION, TestTokens.admin(UUID.randomUUID())));
    }

    private static byte[] examplePaper() throws Exception {
        return new ClassPathResource("dev/fake-report/paper.pdf").getContentAsByteArray();
    }
}
