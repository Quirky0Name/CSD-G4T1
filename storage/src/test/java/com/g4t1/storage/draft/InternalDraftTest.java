package com.g4t1.storage.research;

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
class InternalResearchPaperTest {

    private static final byte[] DRAFT = "%PDF-1.7 my draft".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DRAFT_V2 = "%PDF-1.7 my draft, second version".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NO_FOLDER_DRAFT = "%PDF-1.7 my other draft".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

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
    void aPaperInAFolderGetsThatFoldersResearchPaper() throws Exception {
        UUID paperId = track(owner, folder);
        upload(owner, folder, DRAFT);
        upload(owner, null, NO_FOLDER_DRAFT);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(DRAFT));
    }

    @Test
    void afterAReUploadTheNewestPdfComesBack() throws Exception {
        UUID paperId = track(owner, folder);
        upload(owner, folder, DRAFT);
        upload(owner, folder, DRAFT_V2);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().bytes(DRAFT_V2));
    }

    @Test
    void aPaperInNoFolderGetsTheOwnersNoFolderResearchPaper() throws Exception {
        UUID paperId = track(owner, null);
        upload(owner, folder, DRAFT);
        upload(owner, null, NO_FOLDER_DRAFT);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().bytes(NO_FOLDER_DRAFT));
    }

    @Test
    void anotherUsersResearchPaperIsNeverReturned() throws Exception {
        UUID inFolder = track(owner, folder);
        UUID noFolder = track(owner, null);
        upload(someoneElse, folder, DRAFT);
        upload(someoneElse, null, NO_FOLDER_DRAFT);

        fetch(inFolder)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper in the project of paper " + inFolder));
        fetch(noFolder)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper in the project of paper " + noFolder));
    }

    @Test
    void anUnknownPaperIsTheSameNoPaper404AsTheOtherInternalEndpoints() throws Exception {
        UUID unknown = UUID.randomUUID();

        fetch(unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + unknown));
    }

    @Test
    void aProjectWithNoResearchPaperIs404WithItsOwnDetail() throws Exception {
        UUID paperId = track(owner, folder);

        // a different detail from "No paper <id>", which Research Evaluation reads as the paper being gone
        fetch(paperId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No research paper in the project of paper " + paperId));
    }

    @Test
    void aResearchPaperWhoseFileIsMissingFromDiskIs404() throws Exception {
        UUID paperId = track(owner, folder);
        upload(owner, folder, DRAFT);
        Files.delete(Path.of(uploadDir, researchPapers.findAll().getFirst().getFileKey()));

        fetch(paperId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The research paper for paper " + paperId + " is missing from disk"));
    }

    @Test
    void anIdThatIsNotAUuidIsRejected() throws Exception {
        mvc.perform(get("/internal/papers/not-a-uuid/research-paper").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyServiceTokensAreAccepted() throws Exception {
        UUID paperId = track(owner, folder);
        upload(owner, folder, DRAFT);

        mvc.perform(get("/internal/papers/{id}/research-paper", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/papers/{id}/research-paper", paperId))
                .andExpect(status().isUnauthorized());
    }

    private UUID track(UUID user, UUID folderId) {
        Paper paper = new Paper(user);
        paper.setFolderId(folderId);
        return papers.save(paper).getId();
    }

    private void upload(UUID user, UUID folderId, byte[] pdf) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/research-paper")
                .file(new MockMultipartFile("file", "draft.pdf", "application/pdf", pdf));
        if (folderId != null) {
            request.param("folder_id", folderId.toString());
        }
        mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().is2xxSuccessful());
    }

    private ResultActions fetch(UUID paperId) throws Exception {
        return mvc.perform(get("/internal/papers/{id}/research-paper", paperId)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }
}
