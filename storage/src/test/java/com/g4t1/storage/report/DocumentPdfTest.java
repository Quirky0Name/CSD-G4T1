package com.g4t1.storage.report;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /documents/{id}/pdf: a report document's stored PDF, for the owner of the report's paper only
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentPdfTest {

    private static final String PAPER_DOI = "10.1016/j.ijantimicag.2020.105949";
    private static final byte[] PDF = "%PDF-1.7 current copy".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    ReportRepository reports;

    @Autowired
    ReportDocumentRepository documents;

    @Autowired
    LocalFileStore files;

    private final UUID owner = UUID.randomUUID();
    private long reportId;
    private long documentId;

    @BeforeEach
    void storeDocument() {
        UUID paperId = papers.save(new Paper(owner)).getId();
        reportId = reports.save(new Report(paperId)).getId();
        ReportDocument copy = new ReportDocument(
                InternalReportTest.documentRequest(reportId, "current_version", PAPER_DOI), null, null);
        copy.recordPdf(files.save(PDF), "sha", "https://pmc.example/current.pdf");
        documentId = documents.save(copy).getId();
    }

    @Test
    void theOwnerGetsTheStoredPdf() throws Exception {
        mvc.perform(get("/documents/" + documentId + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF));
    }

    @Test
    void anotherUsersDocumentIsNotFound() throws Exception {
        mvc.perform(get("/documents/" + documentId + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMissingDocumentIsNotFound() throws Exception {
        mvc.perform(get("/documents/" + (documentId + 1000) + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNoticeIsNotFound() throws Exception {
        long notice = documents.save(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "notice", "10.1016/j.ijantimicag.2024.107416"),
                null, null)).getId();

        mvc.perform(get("/documents/" + notice + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aServiceTokenIsForbidden() throws Exception {
        mvc.perform(get("/documents/" + documentId + "/pdf").header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/documents/" + documentId + "/pdf")).andExpect(status().isUnauthorized());
    }
}
