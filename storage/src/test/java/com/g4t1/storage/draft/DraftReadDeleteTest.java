package com.g4t1.storage.research;

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
class ResearchPaperReadDeleteTest {

    private static final byte[] DRAFT = "%PDF-1.7 my draft".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    ResearchPaperRepository researchPapers;

    @Value("${storage.upload-dir}")
    String uploadDir;

    private final UUID owner = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();

    @BeforeEach
    void clean() {
        researchPapers.deleteAll();
    }

    @Test
    void getReturnsTheProjectsResearchPaperAsUploaded() throws Exception {
        String uploaded = upload(owner, folder.toString(), "draft.pdf").andReturn().getResponse().getContentAsString();

        read(owner, folder.toString())
                .andExpect(status().isOk())
                .andExpect(content().json(uploaded, JsonCompareMode.STRICT));
    }

    @Test
    void getReturnsTheNewestUploadAfterAReplace() throws Exception {
        upload(owner, folder.toString(), "draft.pdf");
        String newest = upload(owner, folder.toString(), "draft-v2.pdf").andReturn().getResponse().getContentAsString();

        read(owner, folder.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("draft-v2.pdf"))
                .andExpect(jsonPath("$.uploaded_at").value(JsonPath.<String>read(newest, "$.uploaded_at")));
    }

    @Test
    void noFolderIdGetsTheNoFolderProjectNotAFoldersOne() throws Exception {
        upload(owner, folder.toString(), "in-folder.pdf");
        upload(owner, null, "no-folder.pdf");

        read(owner, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("no-folder.pdf"))
                .andExpect(jsonPath("$.folder_id").value(nullValue()));
        read(owner, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("no-folder.pdf"));
        read(owner, folder.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("in-folder.pdf"));
    }

    @Test
    void getIs404WhenTheProjectHasNoResearchPaper() throws Exception {
        read(owner, folder.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper in folder " + folder));
        read(owner, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper outside folders"));
    }

    @Test
    void getNeverShowsAnotherUsersResearchPaper() throws Exception {
        upload(someoneElse, folder.toString(), "theirs.pdf");
        upload(someoneElse, null, "theirs-no-folder.pdf");

        read(owner, folder.toString()).andExpect(status().isNotFound());
        read(owner, null).andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesTheRowAndThePdf() throws Exception {
        upload(owner, folder.toString(), "draft.pdf");
        String key = researchPapers.findAll().getFirst().getFileKey();

        remove(owner, folder.toString()).andExpect(status().isNoContent());

        assertThat(researchPapers.count()).isZero();
        assertThat(Path.of(uploadDir, key)).doesNotExist();
        read(owner, folder.toString()).andExpect(status().isNotFound());
    }

    @Test
    void deletingTheNoFolderProjectsPaperLeavesTheFoldersAlone() throws Exception {
        upload(owner, folder.toString(), "in-folder.pdf");
        upload(owner, null, "no-folder.pdf");

        remove(owner, null).andExpect(status().isNoContent());

        assertThat(researchPapers.findAll()).singleElement().satisfies(left -> {
            assertThat(left.getFolderId()).isEqualTo(folder);
            assertThat(Path.of(uploadDir, left.getFileKey())).exists();
        });
    }

    @Test
    void deleteIs404WhenTheProjectHasNoResearchPaper() throws Exception {
        remove(owner, folder.toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper in folder " + folder));
        remove(owner, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper outside folders"));
    }

    @Test
    void deleteNeverTouchesAnotherUsersResearchPaper() throws Exception {
        upload(someoneElse, folder.toString(), "theirs.pdf");
        upload(someoneElse, null, "theirs-no-folder.pdf");

        remove(owner, folder.toString()).andExpect(status().isNotFound());
        remove(owner, null).andExpect(status().isNotFound());

        assertThat(researchPapers.findAll()).hasSize(2).allSatisfy(theirs -> {
            assertThat(theirs.getOwnerId()).isEqualTo(someoneElse);
            assertThat(Path.of(uploadDir, theirs.getFileKey())).exists();
        });
    }

    @Test
    void uploadingAfterADeleteStartsANewResearchPaper() throws Exception {
        String first = upload(owner, folder.toString(), "draft.pdf").andReturn().getResponse().getContentAsString();
        remove(owner, folder.toString()).andExpect(status().isNoContent());

        upload(owner, folder.toString(), "fresh.pdf")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(not(JsonPath.<String>read(first, "$.id"))));
    }

    @Test
    void aFolderIdThatIsNotAUuidIsRejected() throws Exception {
        read(owner, "not-a-uuid").andExpect(status().isBadRequest());
        remove(owner, "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void getAndDeleteNeedAUserToken() throws Exception {
        upload(owner, folder.toString(), "draft.pdf");

        mvc.perform(get("/research-paper")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/research-paper")).andExpect(status().isUnauthorized());
        mvc.perform(get("/research-paper").param("folder_id", folder.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/research-paper").param("folder_id", folder.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());

        assertThat(researchPapers.count()).isEqualTo(1);
    }

    private ResultActions upload(UUID user, String folderId, String filename) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/research-paper")
                .file(new MockMultipartFile("file", filename, "application/pdf", DRAFT));
        if (folderId != null) {
            request.param("folder_id", folderId);
        }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions read(UUID user, String folderId) throws Exception {
        return mvc.perform(withFolder(get("/research-paper"), folderId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions remove(UUID user, String folderId) throws Exception {
        return mvc.perform(withFolder(delete("/research-paper"), folderId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private static MockHttpServletRequestBuilder withFolder(MockHttpServletRequestBuilder request, String folderId) {
        return folderId == null ? request : request.param("folder_id", folderId);
    }
}
