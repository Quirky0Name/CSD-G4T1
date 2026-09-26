package com.g4t1.storage.paper;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.grobid.GrobidClient.PdfHeader;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.PaperMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperUploadTest {

    private static final byte[] PDF = "%PDF-1.7 tiny test file".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    @Value("${storage.upload-dir}")
    String uploadDir;

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void clean() {
        papers.deleteAll();
    }

    @Test
    void uploadFindsDoiLooksUpMetadataAndSavesForUploader() throws Exception {
        when(grobid.extractHeader(any())).thenReturn(Optional.of(new PdfHeader("10.1016/ABC.123", "Header title")));
        when(metadata.lookup("10.1016/abc.123")).thenReturn(Optional.of(
                new PaperMetadata("10.1016/abc.123", "W123", "CrossRef title", "The Lancet", "0140-6736", 2020)));

        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doi").value("10.1016/abc.123"))
                .andExpect(jsonPath("$.openalex_id").value("W123"))
                .andExpect(jsonPath("$.title").value("CrossRef title"))
                .andExpect(jsonPath("$.journal").value("The Lancet"))
                .andExpect(jsonPath("$.file_available").value(true));

        Paper saved = papers.findAll().getFirst();
        assertThat(saved.getOwnerId()).isEqualTo(user);
        assertThat(saved.getFileKey()).isNotNull();
    }

    @Test
    void uploadStoresPaperDetailsInDatabaseAndPdfOnDisk() throws Exception {
        when(grobid.extractHeader(any())).thenReturn(Optional.of(new PdfHeader("10.1016/ABC.123", "Header title")));
        when(metadata.lookup("10.1016/abc.123")).thenReturn(Optional.of(
                new PaperMetadata("10.1016/abc.123", "W123", "CrossRef title", "The Lancet", "0140-6736", 2020)));

        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issn").value("0140-6736"))
                .andExpect(jsonPath("$.publication_year").value(2020));

        // findAll reads the row back from the DB, not the in-memory Paper the response was built from
        Paper saved = papers.findAll().getFirst();
        assertThat(saved.getDoi()).isEqualTo("10.1016/abc.123");
        assertThat(saved.getOpenalexId()).isEqualTo("W123");
        assertThat(saved.getTitle()).isEqualTo("CrossRef title");
        assertThat(saved.getJournal()).isEqualTo("The Lancet");
        assertThat(saved.getIssn()).isEqualTo("0140-6736");
        assertThat(saved.getPublicationYear()).isEqualTo(2020);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(Files.readAllBytes(Path.of(uploadDir, saved.getFileKey()))).isEqualTo(PDF);
    }

    @Test
    void uploadStillSavesWhenGrobidFindsNothing() throws Exception {
        when(grobid.extractHeader(any())).thenReturn(Optional.empty());

        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doi").isEmpty())
                .andExpect(jsonPath("$.title").value("paper.pdf"));
    }

    @Test
    void uploadKeepsTheFolderItWasAddedTo() throws Exception {
        when(grobid.extractHeader(any())).thenReturn(Optional.empty());
        UUID folder = UUID.randomUUID();

        mvc.perform(multipart("/papers").file(pdf(PDF)).param("folder_id", folder.toString())
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folder_id").value(folder.toString()));

        assertThat(papers.findAll().getFirst().getFolderId()).isEqualTo(folder);
    }

    @Test
    void nonPdfIsRejectedWithMessage() throws Exception {
        var text = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/papers").file(text).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only PDF files can be uploaded"));
        assertThat(papers.count()).isZero();
    }

    @Test
    void oversizedPdfIsRejectedWithMessage() throws Exception {
        byte[] big = new byte[2048];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        mvc.perform(multipart("/papers").file(pdf(big)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.detail").value(startsWith("PDF is too large")));
    }

    @Test
    void sameDoiTwiceForOneUserIsAConflict() throws Exception {
        when(grobid.extractHeader(any())).thenReturn(Optional.of(new PdfHeader("10.1/dup", null)));
        when(metadata.lookup(any())).thenReturn(Optional.empty());

        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isConflict());
    }

    @Test
    void uploadNeedsAUserToken() throws Exception {
        mvc.perform(multipart("/papers").file(pdf(PDF)))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/papers").file(pdf(PDF)).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    private static MockMultipartFile pdf(byte[] bytes) {
        return new MockMultipartFile("file", "paper.pdf", "application/pdf", bytes);
    }
}
