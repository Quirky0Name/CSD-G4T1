package com.g4t1.storage.draft;

import com.g4t1.storage.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DraftReadDeleteTest {

    private static final byte[] DRAFT = "%PDF-1.7 my draft".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    DraftRepository drafts;

    @Value("${storage.upload-dir}")
    String uploadDir;

    private final UUID owner = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    @BeforeEach
    void clean() {
        drafts.deleteAll();
    }

    @Test
    void getReturnsTheProjectsDraftAsUploaded() throws Exception {
        String uploaded = upload(owner, project.toString(), "draft.pdf").andReturn().getResponse().getContentAsString();

        read(owner, project.toString())
                .andExpect(status().isOk())
                .andExpect(content().json(uploaded, JsonCompareMode.STRICT));
    }

    @Test
    void getReturnsTheNewestUploadAfterAReplace() throws Exception {
        upload(owner, project.toString(), "draft.pdf");
        String newest = upload(owner, project.toString(), "draft-v2.pdf").andReturn().getResponse().getContentAsString();

        read(owner, project.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("draft-v2.pdf"))
                .andExpect(jsonPath("$.uploaded_at").value(JsonPath.<String>read(newest, "$.uploaded_at")));
    }

    @Test
    void noProjectIdGetsTheDefaultProjectNotAnotherProjectsOne() throws Exception {
        upload(owner, project.toString(), "in-project.pdf");
        upload(owner, null, "default-project.pdf");

        read(owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("default-project.pdf"))
                .andExpect(jsonPath("$.project_id").value(nullValue()));
        read(owner, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("default-project.pdf"));
        read(owner, project.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("in-project.pdf"));
    }

    @Test
    void getIs404WhenTheProjectHasNoDraft() throws Exception {
        read(owner, project.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in project " + project));
        read(owner, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in the default project"));
    }

    @Test
    void getNeverShowsAnotherUsersDraft() throws Exception {
        upload(someoneElse, project.toString(), "theirs.pdf");
        upload(someoneElse, null, "theirs-default-project.pdf");

        read(owner, project.toString()).andExpect(status().isNotFound());
        read(owner, null).andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesTheRowAndThePdf() throws Exception {
        upload(owner, project.toString(), "draft.pdf");
        String key = drafts.findAll().getFirst().getFileKey();

        remove(owner, project.toString()).andExpect(status().isNoContent());

        assertThat(drafts.count()).isZero();
        assertThat(Path.of(uploadDir, key)).doesNotExist();
        read(owner, project.toString()).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheDefaultProjectsDraftLeavesOtherProjectsAlone() throws Exception {
        upload(owner, project.toString(), "in-project.pdf");
        upload(owner, null, "default-project.pdf");

        remove(owner, null).andExpect(status().isNoContent());

        assertThat(drafts.findAll()).singleElement().satisfies(left -> {
            assertThat(left.getProjectId()).isEqualTo(project);
            assertThat(Path.of(uploadDir, left.getFileKey())).exists();
        });
    }

    @Test
    void deleteIs404WhenTheProjectHasNoDraft() throws Exception {
        remove(owner, project.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in project " + project));
        remove(owner, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in the default project"));
    }

    @Test
    void deleteNeverTouchesAnotherUsersDraft() throws Exception {
        upload(someoneElse, project.toString(), "theirs.pdf");
        upload(someoneElse, null, "theirs-default-project.pdf");

        remove(owner, project.toString()).andExpect(status().isNotFound());
        remove(owner, null).andExpect(status().isNotFound());

        assertThat(drafts.findAll()).hasSize(2).allSatisfy(theirs -> {
            assertThat(theirs.getOwnerId()).isEqualTo(someoneElse);
            assertThat(Path.of(uploadDir, theirs.getFileKey())).exists();
        });
    }

    @Test
    void uploadingAfterADeleteStartsANewDraft() throws Exception {
        String first = upload(owner, project.toString(), "draft.pdf").andReturn().getResponse().getContentAsString();
        remove(owner, project.toString()).andExpect(status().isNoContent());

        upload(owner, project.toString(), "fresh.pdf")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(JsonPath.<String>read(first, "$.id"))));
    }

    @Test
    void aProjectIdThatIsNotAUuidIsRejected() throws Exception {
        read(owner, "not-a-uuid").andExpect(status().isBadRequest());
        remove(owner, "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void getAndDeleteNeedAUserToken() throws Exception {
        upload(owner, project.toString(), "draft.pdf");

        mvc.perform(get("/draft")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/draft")).andExpect(status().isUnauthorized());
        mvc.perform(get("/draft").param("project_id", project.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/draft").param("project_id", project.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());

        assertThat(drafts.count()).isEqualTo(1);
    }

    private ResultActions upload(UUID user, String projectId, String filename) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/draft")
                .file(new MockMultipartFile("file", filename, "application/pdf", DRAFT));
        if (projectId != null) {
            request.param("project_id", projectId);
        }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions read(UUID user, String projectId) throws Exception {
        return mvc.perform(withProject(get("/draft"), projectId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions remove(UUID user, String projectId) throws Exception {
        return mvc.perform(withProject(delete("/draft"), projectId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private static MockHttpServletRequestBuilder withProject(MockHttpServletRequestBuilder request, String projectId) {
        return projectId == null ? request : request.param("project_id", projectId);
    }
}
