package com.g4t1.storage.paper;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import com.g4t1.storage.metadata.PaperMetadata;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalPaperPdfTest {

    private static final byte[] UPLOADED = "%PDF-1.7 uploaded copy".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OPEN_ACCESS = "%PDF-1.7 open access copy".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    @MockitoBean
    OpenAccessPdfClient openAccess;

    @Value("${storage.upload-dir}")
    String uploadDir;

    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void noDoiInUploads() {
        when(grobid.extractHeader(any())).thenReturn(Optional.empty());
    }

    @Test
    void anUploadedPapersPdfComesBackAsStored() throws Exception {
        UUID paperId = upload(owner, UPLOADED);

        fetch(paperId)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(UPLOADED));
    }

    @Test
    void aPaperTrackedByDoiGetsItsDownloadedOpenAccessPdf() throws Exception {
        when(metadata.lookup("10.1000/xyz123")).thenReturn(Optional.of(
                new PaperMetadata("10.1000/xyz123", "W1", "A paper", "A journal", "0000-0000", 2020)));
        when(openAccess.download("10.1000/xyz123")).thenReturn(Optional.of(OPEN_ACCESS));
        String created = mvc.perform(post("/papers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doi\": \"10.1000/xyz123\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        fetch(UUID.fromString(JsonPath.read(created, "$.id")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(OPEN_ACCESS));
    }

    @Test
    void aServiceTokenReadsAnyUsersPaper() throws Exception {
        byte[] theirs = "%PDF-1.7 someone else's copy".getBytes(StandardCharsets.US_ASCII);
        UUID mine = upload(owner, UPLOADED);
        UUID someoneElses = upload(UUID.randomUUID(), theirs);

        fetch(mine).andExpect(status().isOk()).andExpect(content().bytes(UPLOADED));
        fetch(someoneElses).andExpect(status().isOk()).andExpect(content().bytes(theirs));
    }

    @Test
    void anUnknownPaperIsTheSameNoPaper404AsTheOtherInternalEndpoints() throws Exception {
        UUID unknown = UUID.randomUUID();

        fetch(unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + unknown));
    }

    @Test
    void aPaperWithNoStoredPdfIs404WithItsOwnDetail() throws Exception {
        UUID paperId = papers.save(new Paper(owner)).getId();

        // a different detail from "No paper <id>", which Research Evaluation reads as the paper being gone
        fetch(paperId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Paper " + paperId + " has no stored PDF"));
    }

    @Test
    void aPdfMissingFromDiskIs404WithItsOwnDetail() throws Exception {
        UUID paperId = upload(owner, UPLOADED);
        Files.delete(Path.of(uploadDir, papers.findById(paperId).orElseThrow().getFileKey()));

        fetch(paperId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The PDF for paper " + paperId + " is missing from disk"));
    }

    @Test
    void anIdThatIsNotAUuidIsRejected() throws Exception {
        mvc.perform(get("/internal/papers/not-a-uuid/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyServiceTokensAreAccepted() throws Exception {
        UUID paperId = upload(owner, UPLOADED);

        // not even the paper's owner: the frontend has no PDF endpoint of its own
        mvc.perform(get("/internal/papers/{id}/pdf", paperId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/papers/{id}/pdf", paperId))
                .andExpect(status().isUnauthorized());
    }

    private UUID upload(UUID user, byte[] pdf) throws Exception {
        String created = mvc.perform(multipart("/papers")
                        .file(new MockMultipartFile("file", "paper.pdf", "application/pdf", pdf))
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(created, "$.id"));
    }

    private ResultActions fetch(UUID paperId) throws Exception {
        return mvc.perform(get("/internal/papers/{id}/pdf", paperId).header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }
}
