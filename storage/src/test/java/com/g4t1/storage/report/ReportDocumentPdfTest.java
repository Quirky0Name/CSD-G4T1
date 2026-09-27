package com.g4t1.storage.report;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import com.g4t1.storage.metadata.OpenAccessPdfClient.DownloadedPdf;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /internal/documents downloads a new version's or the current copy's PDF when it creates the
// row, and only then; GET /internal/documents/{id}/pdf only reads
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportDocumentPdfTest {

    private static final String PAPER_DOI = "10.1016/j.ijantimicag.2020.105949";
    private static final byte[] PDF = "%PDF-1.7 current copy".getBytes(StandardCharsets.US_ASCII);
    private static final String SOURCE = "https://pmc.example/articles/PMC7/pdf/main.pdf";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PaperRepository papers;

    @Autowired
    ReportRepository reports;

    @Autowired
    ReportDocumentRepository documents;

    @Autowired
    LocalFileStore files;

    @MockitoBean
    OpenAccessPdfClient openAccess;

    @Value("${storage.upload-dir}")
    String uploadDir;

    private long reportId;

    @BeforeEach
    void clean() {
        documents.deleteAll();
        reports.deleteAll();
        papers.deleteAll();
        UUID paperId = papers.save(new Paper(UUID.randomUUID())).getId();
        reportId = reports.save(new Report(paperId)).getId();
    }

    @ParameterizedTest
    @ValueSource(strings = {"current_version", "new_version"})
    void aNewRowIsDownloadedOnceAndStored(String kind) throws Exception {
        when(openAccess.downloadWithSource(PAPER_DOI)).thenReturn(Optional.of(new DownloadedPdf(PDF, SOURCE)));

        String body = store(body(kind))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pdf_status").value("ok"))
                .andExpect(jsonPath("$.sha256").value(sha256(PDF)))
                .andExpect(jsonPath("$.pdf_source_url").value(SOURCE))
                .andExpect(jsonPath("$.file_key").isString())
                .andExpect(jsonPath("$.pdf_fetched_at").isString())
                .andReturn().getResponse().getContentAsString();

        verify(openAccess, times(1)).downloadWithSource(PAPER_DOI);
        // the response is the row as stored, and the file is really there
        ReportDocument saved = documents.findAll().getFirst();
        JsonNode returned = json.readTree(body);
        assertThat(saved.getPdfStatus()).isEqualTo(PdfStatus.OK);
        assertThat(returned.get("file_key").asString()).isEqualTo(saved.getFileKey());
        assertThat(returned.get("pdf_fetched_at").asString()).isEqualTo(saved.getPdfFetchedAt().toString());
        assertThat(files.read(saved.getFileKey())).hasValue(PDF);
    }

    @Test
    void noDownloadablePdfIsNotFound() throws Exception {
        when(openAccess.downloadWithSource(PAPER_DOI)).thenReturn(Optional.empty());

        store(body("current_version"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pdf_status").value("not_found"))
                .andExpect(jsonPath("$.file_key").value(nullValue()))
                .andExpect(jsonPath("$.sha256").value(nullValue()))
                .andExpect(jsonPath("$.pdf_source_url").value(nullValue()))
                .andExpect(jsonPath("$.pdf_fetched_at").value(nullValue()));
        assertThat(documents.findAll().getFirst().getPdfStatus()).isEqualTo(PdfStatus.NOT_FOUND);
    }

    @Test
    void aNoticeIsNeverDownloaded() throws Exception {
        Map<String, Object> notice = body("notice");
        notice.put("doi", "10.1016/j.ijantimicag.2024.107416");

        store(notice)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pdf_status").value("skipped"));
        verify(openAccess, never()).downloadWithSource(any());
    }

    @ParameterizedTest
    @EnumSource(value = PdfStatus.class, names = {"OK", "NOT_FOUND"})
    void sendingAStoredRowAgainDownloadsNothing(PdfStatus firstOutcome) throws Exception {
        when(openAccess.downloadWithSource(PAPER_DOI)).thenReturn(firstOutcome == PdfStatus.OK
                ? Optional.of(new DownloadedPdf(PDF, SOURCE)) : Optional.empty());
        String first = store(body("current_version")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String again = store(body("current_version")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(openAccess, times(1)).downloadWithSource(PAPER_DOI);
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        assertThat(documents.count()).isEqualTo(1);
    }

    @Test
    void aRowLeftPendingIsNeverDownloadedAgain() throws Exception {
        // what a crash between saving the row and downloading leaves behind
        ReportDocument pending = documents.saveAndFlush(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "current_version", PAPER_DOI), null));

        store(body("current_version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pending.getId()))
                .andExpect(jsonPath("$.pdf_status").value("pending"));
        verify(openAccess, never()).downloadWithSource(any());
    }

    @Test
    void aFailingDownloadLeavesTheSavedRowPending() throws Exception {
        when(openAccess.downloadWithSource(PAPER_DOI)).thenThrow(new IllegalStateException("boom"));

        // MockMvc rethrows what a running server would answer as a 500
        assertThatThrownBy(() -> store(body("current_version"))).hasRootCauseMessage("boom");

        ReportDocument left = documents.findAll().getFirst();
        assertThat(left.getPdfStatus()).isEqualTo(PdfStatus.PENDING);
        assertThat(left.getFileKey()).isNull();
        assertThat(left.getSha256()).isNull();
    }

    @Test
    void theStoredPdfIsReadBackWithoutDownloading() throws Exception {
        when(openAccess.downloadWithSource(PAPER_DOI)).thenReturn(Optional.of(new DownloadedPdf(PDF, SOURCE)));
        long id = json.readTree(store(body("current_version")).andReturn().getResponse().getContentAsString())
                .get("id").asLong();

        readPdf(id)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF));
        verify(openAccess, times(1)).downloadWithSource(PAPER_DOI);
    }

    @Test
    void aDocumentWithNoStoredPdfIs404WithItsOwnDetail() throws Exception {
        long notFound = documents.saveAndFlush(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "current_version", PAPER_DOI), null)).getId();
        ReportDocument foundNothing = documents.findById(notFound).orElseThrow();
        foundNothing.recordNoPdf();
        documents.saveAndFlush(foundNothing);
        long pending = documents.saveAndFlush(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "new_version", "10.1/v2"), null)).getId();
        long notice = documents.saveAndFlush(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "notice", "10.1/notice"), null)).getId();

        for (long id : new long[]{notFound, pending, notice}) {
            readPdf(id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Document " + id + " has no stored PDF"));
        }
        verify(openAccess, never()).downloadWithSource(any());
    }

    @Test
    void aPdfMissingFromDiskIs404WithItsOwnDetail() throws Exception {
        when(openAccess.downloadWithSource(PAPER_DOI)).thenReturn(Optional.of(new DownloadedPdf(PDF, SOURCE)));
        store(body("current_version")).andExpect(status().isCreated());
        ReportDocument saved = documents.findAll().getFirst();
        Files.delete(Path.of(uploadDir, saved.getFileKey()));

        readPdf(saved.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("The PDF for document " + saved.getId() + " is missing from disk"));
    }

    @Test
    void anUnknownOrNonNumericDocumentId() throws Exception {
        readPdf(999_999)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No document 999999"));
        mvc.perform(get("/internal/documents/abc/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readingNeedsAServiceToken() throws Exception {
        mvc.perform(get("/internal/documents/1/pdf")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/documents/1/pdf").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/documents/1/pdf")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    private ResultActions store(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/internal/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private ResultActions readPdf(long documentId) throws Exception {
        return mvc.perform(get("/internal/documents/{id}/pdf", documentId)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private Map<String, Object> body(String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report_id", reportId);
        body.put("kind", kind);
        body.put("doi", PAPER_DOI);
        body.put("crossref_status", "ok");
        body.put("crossref_record", Map.of("title", "RETRACTED: Hydroxychloroquine and azithromycin"));
        body.put("update_to_includes_paper", null);
        body.put("text_status", "not_open_access");
        body.put("text", null);
        body.put("text_truncated", false);
        return body;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
