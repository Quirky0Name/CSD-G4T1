package com.g4t1.storage.draft;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalDraftTest {

    private static final byte[] DRAFT = "%PDF-1.7 my draft".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DRAFT_V2 = "%PDF-1.7 my draft, second version".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DEFAULT_PROJECT_DRAFT = "%PDF-1.7 my other draft".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

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
    void aPaperInAProjectGetsThatProjectsDraft() throws Exception {
        UUID paperId = track(owner, project);
        upload(owner, project, DRAFT);
        upload(owner, null, DEFAULT_PROJECT_DRAFT);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(DRAFT));
    }

    @Test
    void afterAReUploadTheNewestPdfComesBack() throws Exception {
        UUID paperId = track(owner, project);
        upload(owner, project, DRAFT);
        upload(owner, project, DRAFT_V2);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().bytes(DRAFT_V2));
    }

    @Test
    void aPaperInTheDefaultProjectGetsThatProjectsDraft() throws Exception {
        UUID paperId = track(owner, null);
        upload(owner, project, DRAFT);
        upload(owner, null, DEFAULT_PROJECT_DRAFT);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().bytes(DEFAULT_PROJECT_DRAFT));
    }

    @Test
    void anotherUsersDraftIsNeverReturned() throws Exception {
        UUID inProject = track(owner, project);
        UUID inDefaultProject = track(owner, null);
        upload(someoneElse, project, DRAFT);
        upload(someoneElse, null, DEFAULT_PROJECT_DRAFT);

        fetch(inProject)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in the project of paper " + inProject));
        fetch(inDefaultProject)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in the project of paper " + inDefaultProject));
    }

    @Test
    void anUnknownPaperIsTheSameNoPaper404AsTheOtherInternalEndpoints() throws Exception {
        UUID unknown = UUID.randomUUID();

        fetch(unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + unknown));
    }

    @Test
    void aProjectWithNoDraftIs404WithItsOwnDetail() throws Exception {
        UUID paperId = track(owner, project);

        // a different detail from "No paper <id>", which Research Evaluation reads as the paper being gone
        fetch(paperId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No draft in the project of paper " + paperId));
    }

    @Test
    void aDraftWhoseFileIsMissingFromDiskIs404() throws Exception {
        UUID paperId = track(owner, project);
        upload(owner, project, DRAFT);
        Files.delete(Path.of(uploadDir, drafts.findAll().getFirst().getFileKey()));

        fetch(paperId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The draft for paper " + paperId + " is missing from disk"));
    }

    @Test
    void anIdThatIsNotAUuidIsRejected() throws Exception {
        mvc.perform(get("/internal/papers/not-a-uuid/draft").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyServiceTokensAreAccepted() throws Exception {
        UUID paperId = track(owner, project);
        upload(owner, project, DRAFT);

        mvc.perform(get("/internal/papers/{id}/draft", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/papers/{id}/draft", paperId))
                .andExpect(status().isUnauthorized());
    }

    private UUID track(UUID user, UUID projectId) {
        Paper paper = new Paper(user);
        paper.setProjectId(projectId);
        return papers.save(paper).getId();
    }

    private void upload(UUID user, UUID projectId, byte[] pdf) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/draft")
                .file(new MockMultipartFile("file", "draft.pdf", "application/pdf", pdf));
        if (projectId != null) {
            request.param("project_id", projectId.toString());
        }
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().is2xxSuccessful());
    }

    private ResultActions fetch(UUID paperId) throws Exception {
        return mvc.perform(get("/internal/papers/{id}/draft", paperId)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }
}
