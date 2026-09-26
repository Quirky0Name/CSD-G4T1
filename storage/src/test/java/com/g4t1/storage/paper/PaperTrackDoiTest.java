package com.g4t1.storage.paper;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import com.g4t1.storage.metadata.PaperMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaperTrackDoiTest {

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

    private static final byte[] PDF = "%PDF-1.7 open access copy".getBytes(StandardCharsets.US_ASCII);

    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void clean() {
        papers.deleteAll();
        when(openAccess.download(any())).thenReturn(Optional.of(PDF));
    }

    @Test
    void trackingByDoiSavesCrossRefMetadataAndTheOpenAccessPdf() throws Exception {
        when(metadata.lookup("10.1016/s0140-6736(20)31180-6")).thenReturn(Optional.of(new PaperMetadata(
                "10.1016/s0140-6736(20)31180-6", "W3027680906", "Hydroxychloroquine or chloroquine",
                "The Lancet", "0140-6736", 2020)));

        track("https://doi.org/10.1016/S0140-6736(20)31180-6")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.doi").value("10.1016/s0140-6736(20)31180-6"))
                .andExpect(jsonPath("$.openalex_id").value("W3027680906"))
                .andExpect(jsonPath("$.journal").value("The Lancet"))
                .andExpect(jsonPath("$.publication_year").value(2020))
                .andExpect(jsonPath("$.file_available").value(true));

        Paper saved = papers.findAll().getFirst();
        assertThat(saved.getOwnerId()).isEqualTo(user);
        assertThat(saved.getFolderId()).isNull();
        assertThat(Files.readAllBytes(Path.of(uploadDir, saved.getFileKey()))).isEqualTo(PDF);
    }

    @Test
    void doiWithNoOpenAccessPdfIsNotTracked() throws Exception {
        when(metadata.lookup(any())).thenReturn(Optional.of(
                new PaperMetadata("10.1000/xyz123", null, "A paper", null, null, null)));
        when(openAccess.download("10.1000/xyz123")).thenReturn(Optional.empty());

        track("10.1000/xyz123")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Couldn't find an open-access PDF for DOI 10.1000/xyz123, "
                        + "so it can't be tracked. Try a different paper"));
        assertThat(papers.count()).isZero();
    }

    @Test
    void trackingByDoiKeepsTheFolder() throws Exception {
        when(metadata.lookup(any())).thenReturn(Optional.of(
                new PaperMetadata("10.1000/xyz123", null, "A paper", null, null, null)));
        UUID folder = UUID.randomUUID();

        mvc.perform(post("/papers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doi\":\"10.1000/xyz123\",\"folder_id\":\"" + folder + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folder_id").value(folder.toString()));
    }

    @Test
    void blankFolderIsTreatedAsNoFolder() throws Exception {
        when(metadata.lookup(any())).thenReturn(Optional.of(
                new PaperMetadata("10.1000/xyz123", null, "A paper", null, null, null)));

        mvc.perform(post("/papers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"doi\":\"10.1000/xyz123\",\"folder_id\":\"\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folder_id").isEmpty());
    }

    @Test
    void somethingThatIsNotADoiGetsAReadableError() throws Exception {
        track("not a doi")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("\"not a doi\" isn't a DOI, it should look like 10.1000/xyz123"));
        verify(metadata, never()).lookup(any());
    }

    @Test
    void missingDoiGetsAReadableError() throws Exception {
        mvc.perform(post("/papers").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("doi is required"));
        verify(metadata, never()).lookup(any());
    }

    @Test
    void doiCrossRefDoesNotKnowGetsAReadableError() throws Exception {
        when(metadata.lookup("10.9999/nope")).thenReturn(Optional.empty());

        track("10.9999/nope")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("CrossRef has no paper with DOI 10.9999/nope"));
        assertThat(papers.count()).isZero();
    }

    @Test
    void crossRefOutageIsReportedAsUnavailable() throws Exception {
        when(metadata.lookup(any())).thenThrow(new ResourceAccessException("timed out"));

        track("10.1000/xyz123")
                .andExpect(status().isServiceUnavailable());
        assertThat(papers.count()).isZero();
    }

    @Test
    void sameDoiTwiceForOneUserIsAConflict() throws Exception {
        when(metadata.lookup(any())).thenReturn(Optional.of(
                new PaperMetadata("10.1000/xyz123", null, "A paper", null, null, null)));

        track("10.1000/xyz123").andExpect(status().isCreated());
        track("10.1000/XYZ123").andExpect(status().isConflict());
    }

    @Test
    void serviceTokensCannotTrackPapers() throws Exception {
        mvc.perform(post("/papers").contentType(MediaType.APPLICATION_JSON).content("{\"doi\":\"10.1000/x\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    private ResultActions track(String doi) throws Exception {
        return mvc.perform(post("/papers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"doi\":\"" + doi + "\"}")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }
}
