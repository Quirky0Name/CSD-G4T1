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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchPaperUploadTest {

    private static final byte[] DRAFT = "%PDF-1.7 my draft".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DRAFT_V2 = "%PDF-1.7 my draft, second version".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    ResearchPaperRepository researchPapers;

    @Value("${storage.upload-dir}")
    String uploadDir;

    private final UUID owner = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();

    @BeforeEach
    void clean() {
        researchPapers.deleteAll();
    }

    @Test
    void firstUploadStoresThePdfOnDiskAndItsKeyInTheDatabase() throws Exception {
        Instant before = Instant.now();

        upload(owner, folder.toString(), pdf("draft.pdf", DRAFT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.folder_id").value(folder.toString()))
                .andExpect(jsonPath("$.filename").value("draft.pdf"))
                .andExpect(jsonPath("$.uploaded_at").isString());

        ResearchPaper saved = researchPapers.findAll().getFirst();
        assertThat(saved.getOwnerId()).isEqualTo(owner);
        assertThat(saved.getFolderId()).isEqualTo(folder);
        assertThat(saved.getUploadedAt()).isBetween(before.minusMillis(1), Instant.now());
        assertThat(Files.readAllBytes(file(saved.getFileKey()))).isEqualTo(DRAFT);
    }

    @Test
    void uploadingAgainToTheSameFolderReplacesTheFileAndDeletesTheOldOne() throws Exception {
        String first = upload(owner, folder.toString(), pdf("draft.pdf", DRAFT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String oldKey = researchPapers.findAll().getFirst().getFileKey();

        upload(owner, folder.toString(), pdf("draft-v2.pdf", DRAFT_V2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")))
                .andExpect(jsonPath("$.folder_id").value(folder.toString()))
                .andExpect(jsonPath("$.filename").value("draft-v2.pdf"));

        assertThat(researchPapers.count()).isEqualTo(1);
        ResearchPaper saved = researchPapers.findAll().getFirst();
        assertThat(saved.getFilename()).isEqualTo("draft-v2.pdf");
        assertThat(saved.getFileKey()).isNotEqualTo(oldKey);
        assertThat(Files.readAllBytes(file(saved.getFileKey()))).isEqualTo(DRAFT_V2);
        assertThat(file(oldKey)).doesNotExist();
    }

    @Test
    void eachFolderIsItsOwnProject() throws Exception {
        UUID otherFolder = UUID.randomUUID();

        upload(owner, folder.toString(), pdf("a.pdf", DRAFT)).andExpect(status().isCreated());
        upload(owner, otherFolder.toString(), pdf("b.pdf", DRAFT_V2)).andExpect(status().isCreated());

        assertThat(researchPapers.findAll())
                .extracting(ResearchPaper::getFolderId, ResearchPaper::getFilename)
                .containsExactlyInAnyOrder(
                        tuple(folder, "a.pdf"),
                        tuple(otherFolder, "b.pdf"));
    }

    @Test
    void noFolderIsOneProjectPerUserWhetherFolderIdIsLeftOutOrEmpty() throws Exception {
        String first = upload(owner, null, pdf("draft.pdf", DRAFT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folder_id").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        upload(owner, "", pdf("draft-v2.pdf", DRAFT_V2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")))
                .andExpect(jsonPath("$.folder_id").value(nullValue()));

        assertThat(researchPapers.count()).isEqualTo(1);
        assertThat(researchPapers.findAll().getFirst().getFolderId()).isNull();
    }

    @Test
    void noFolderIsSeparateFromTheUsersFolders() throws Exception {
        upload(owner, folder.toString(), pdf("in-folder.pdf", DRAFT)).andExpect(status().isCreated());
        upload(owner, null, pdf("no-folder.pdf", DRAFT_V2)).andExpect(status().isCreated());

        assertThat(researchPapers.count()).isEqualTo(2);
    }

    @Test
    void anotherUsersUploadNeverReplacesThisOne() throws Exception {
        UUID someoneElse = UUID.randomUUID();
        upload(owner, folder.toString(), pdf("mine.pdf", DRAFT)).andExpect(status().isCreated());
        upload(owner, null, pdf("mine-no-folder.pdf", DRAFT)).andExpect(status().isCreated());

        // Storage can't check who owns a folder id, so the same id under another user is another project
        upload(someoneElse, folder.toString(), pdf("theirs.pdf", DRAFT_V2)).andExpect(status().isCreated());
        upload(someoneElse, null, pdf("theirs-no-folder.pdf", DRAFT_V2)).andExpect(status().isCreated());

        assertThat(researchPapers.count()).isEqualTo(4);
        assertThat(researchPapers.findAll())
                .filteredOn(saved -> saved.getOwnerId().equals(owner))
                .extracting(ResearchPaper::getFilename)
                .containsExactlyInAnyOrder("mine.pdf", "mine-no-folder.pdf");
    }

    @Test
    void nonPdfIsRejectedAndNothingIsStored() throws Exception {
        long filesBefore = storedFileCount();
        var text = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        upload(owner, folder.toString(), text)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only PDF files can be uploaded"));

        assertThat(researchPapers.count()).isZero();
        assertThat(storedFileCount()).isEqualTo(filesBefore);
    }

    @Test
    void oversizedPdfIsRejectedAndNothingIsStored() throws Exception {
        long filesBefore = storedFileCount();
        byte[] big = new byte[2048];
        System.arraycopy(DRAFT, 0, big, 0, DRAFT.length);

        upload(owner, folder.toString(), pdf("big.pdf", big))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.detail").value(startsWith("PDF is too large")));

        assertThat(researchPapers.count()).isZero();
        assertThat(storedFileCount()).isEqualTo(filesBefore);
    }

    @Test
    void aRefusedUploadLeavesTheCurrentResearchPaperAsItWas() throws Exception {
        upload(owner, folder.toString(), pdf("draft.pdf", DRAFT)).andExpect(status().isCreated());
        ResearchPaper current = researchPapers.findAll().getFirst();

        upload(owner, folder.toString(), new MockMultipartFile("file", "notes.txt", "text/plain", "hi".getBytes()))
                .andExpect(status().isBadRequest());

        ResearchPaper after = researchPapers.findAll().getFirst();
        assertThat(after.getFileKey()).isEqualTo(current.getFileKey());
        assertThat(after.getFilename()).isEqualTo("draft.pdf");
        assertThat(Files.readAllBytes(file(after.getFileKey()))).isEqualTo(DRAFT);
    }

    @Test
    void aFolderIdThatIsNotAUuidIsRejected() throws Exception {
        upload(owner, "not-a-uuid", pdf("draft.pdf", DRAFT))
                .andExpect(status().isBadRequest());

        assertThat(researchPapers.count()).isZero();
    }

    @Test
    void aMissingFileIsRejected() throws Exception {
        mvc.perform(multipart("/research-paper").param("folder_id", folder.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());

        assertThat(researchPapers.count()).isZero();
    }

    @Test
    void uploadNeedsAUserToken() throws Exception {
        mvc.perform(multipart("/research-paper").file(pdf("draft.pdf", DRAFT)))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/research-paper").file(pdf("draft.pdf", DRAFT))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/research-paper").file(pdf("draft.pdf", DRAFT))
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());

        assertThat(researchPapers.count()).isZero();
    }

    private ResultActions upload(UUID user, String folderId, MockMultipartFile file) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/research-paper").file(file);
        if (folderId != null) {
            request.param("folder_id", folderId);
        }
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private Path file(String key) {
        return Path.of(uploadDir, key);
    }

    private long storedFileCount() throws Exception {
        try (Stream<Path> stored = Files.list(Path.of(uploadDir))) {
            return stored.count();
        }
    }

    private static MockMultipartFile pdf(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/pdf", bytes);
    }
}
