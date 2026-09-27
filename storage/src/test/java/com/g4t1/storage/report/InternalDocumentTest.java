package com.g4t1.storage.report;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /internal/documents: storing the row (ReportDocumentPdfTest covers the PDF download and read)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalDocumentTest {

    // IJAA's retraction notice, trimmed; stored as sent
    private static final Map<String, Object> CROSSREF_RECORD = Map.of(
            "title", "Retraction notice to ‘Hydroxychloroquine and azithromycin as a treatment of COVID-19’",
            "update_to", List.of(Map.of("doi", "10.1016/j.ijantimicag.2020.105949", "type", "retraction",
                    "source", "retraction-watch")),
            "published", List.of(2024, 12));

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

    // never the real download in tests; it returns nothing unless a test says otherwise
    @MockitoBean
    OpenAccessPdfClient openAccess;

    private long reportId;

    @BeforeEach
    void clean() {
        documents.deleteAll();
        reports.deleteAll();
        papers.deleteAll();
        UUID paperId = papers.save(new Paper(UUID.randomUUID())).getId();
        reportId = reports.save(new Report(paperId)).getId();
    }

    @Test
    void aNewDocumentIsStoredAndTheEntireRowReturned() throws Exception {
        String body = store(body())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.report_id").value(reportId))
                .andExpect(jsonPath("$.kind").value("notice"))
                .andExpect(jsonPath("$.doi").value("10.1016/j.ijantimicag.2024.107416"))
                .andExpect(jsonPath("$.crossref_status").value("ok"))
                .andExpect(jsonPath("$.update_to_includes_paper").value(true))
                .andExpect(jsonPath("$.text_status").value("ok"))
                .andExpect(jsonPath("$.text").value("This article has been retracted."))
                .andExpect(jsonPath("$.text_truncated").value(false))
                .andExpect(jsonPath("$.pdf_status").value("skipped"))
                .andExpect(jsonPath("$.file_key").value(nullValue()))
                .andExpect(jsonPath("$.sha256").value(nullValue()))
                .andExpect(jsonPath("$.pdf_source_url").value(nullValue()))
                .andExpect(jsonPath("$.pdf_fetched_at").value(nullValue()))
                .andExpect(jsonPath("$.created_at").isString())
                .andReturn().getResponse().getContentAsString();

        // the record comes back as the JSON that was sent, not as text
        JsonNode returned = json.readTree(body);
        assertThat(returned.get("crossref_record")).isEqualTo(json.valueToTree(CROSSREF_RECORD));

        // the response is the row as stored: read back from the DB, not the object it was built from
        ReportDocument saved = documents.findAll().getFirst();
        assertThat(saved.getReportId()).isEqualTo(reportId);
        assertThat(saved.getKind()).isEqualTo(DocumentKind.NOTICE);
        assertThat(saved.getPdfStatus()).isEqualTo(PdfStatus.SKIPPED);
        assertThat(json.readTree(saved.getCrossrefRecord())).isEqualTo(json.valueToTree(CROSSREF_RECORD));
        assertThat(returned.get("created_at").asString()).isEqualTo(saved.getCreatedAt().toString());
        assertThat(returned.get("id").asLong()).isEqualTo(saved.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"new_version", "current_version"})
    void aNewVersionOrTheCurrentCopyGetsItsPdfLookedUp(String kind) throws Exception {
        Map<String, Object> body = body();
        body.put("kind", kind);
        body.put("update_to_includes_paper", null);

        // the mocked download finds nothing here; ReportDocumentPdfTest covers the downloads
        store(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value(kind))
                .andExpect(jsonPath("$.pdf_status").value("not_found"));
        verify(openAccess).downloadWithSource("10.1016/j.ijantimicag.2024.107416");
    }

    @Test
    void theSameDoiInTheReportReturnsTheStoredRowUnchanged() throws Exception {
        store(body()).andExpect(status().isCreated());
        ReportDocument first = documents.findAll().getFirst();

        Map<String, Object> different = body();
        different.put("text", "A different text.");
        different.put("crossref_status", "error");
        different.put("crossref_record", null);
        different.put("kind", "current_version");
        store(different)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.getId()))
                .andExpect(jsonPath("$.kind").value("notice"))
                .andExpect(jsonPath("$.text").value("This article has been retracted."))
                .andExpect(jsonPath("$.crossref_status").value("ok"))
                .andExpect(jsonPath("$.pdf_status").value("skipped"))
                .andExpect(jsonPath("$.created_at").value(first.getCreatedAt().toString()));

        assertThat(documents.count()).isEqualTo(1);
        assertThat(documents.findAll().getFirst().getText()).isEqualTo("This article has been retracted.");
    }

    @Test
    void theSameDoiInAnotherReportIsASeparateRow() throws Exception {
        long otherReport = reports.save(new Report(reports.findById(reportId).orElseThrow().getPaperId())).getId();
        Map<String, Object> other = body();
        other.put("report_id", otherReport);

        store(body()).andExpect(status().isCreated());
        store(other).andExpect(status().isCreated());

        assertThat(documents.count()).isEqualTo(2);
    }

    @Test
    void optionalFieldsCanBeNull() throws Exception {
        Map<String, Object> body = body();
        body.put("crossref_status", "not_found");
        body.put("crossref_record", null);
        body.put("update_to_includes_paper", null);
        body.put("text_status", "not_indexed");
        body.put("text", null);

        store(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.crossref_record").value(nullValue()))
                .andExpect(jsonPath("$.update_to_includes_paper").value(nullValue()))
                .andExpect(jsonPath("$.text").value(nullValue()));
        // a JSON null is stored as SQL null, not the text "null"
        assertThat(documents.findAll().getFirst().getCrossrefRecord()).isNull();
    }

    @Test
    void anUnknownReportIsNotFound() throws Exception {
        Map<String, Object> body = body();
        body.put("report_id", reportId + 1000);

        store(body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No report " + (reportId + 1000)));
        assertThat(documents.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"report_id", "kind", "doi", "crossref_status", "text_status", "text_truncated"})
    void aMissingRequiredFieldIsABadRequest(String field) throws Exception {
        Map<String, Object> body = body();
        body.remove(field);

        store(body).andExpect(status().isBadRequest());
        assertThat(documents.count()).isZero();
    }

    @Test
    void badValuesAreABadRequest() throws Exception {
        for (Map.Entry<String, Object> bad : List.<Map.Entry<String, Object>>of(
                Map.entry("kind", "retraction"),
                Map.entry("kind", "NOTICE"),
                Map.entry("crossref_status", "timeout"),
                Map.entry("text_status", "missing"),
                Map.entry("doi", "  "),
                Map.entry("doi", "10.1/" + "x".repeat(251)))) {
            Map<String, Object> body = body();
            body.put(bad.getKey(), bad.getValue());
            store(body).andExpect(status().isBadRequest());
        }
        assertThat(documents.count()).isZero();
    }

    @Test
    void storingNeedsAServiceToken() throws Exception {
        String payload = json.writeValueAsString(body());

        mvc.perform(post("/internal/documents").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/documents").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/documents").contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertThat(documents.count()).isZero();
    }

    @Test
    void theDatabaseItselfRejectsASecondRowForTheSameDoiInAReport() throws Exception {
        store(body()).andExpect(status().isCreated());

        // the constraint that backs up ReportDocumentService when two requests race past its lookup
        assertThatThrownBy(() -> documents.saveAndFlush(new ReportDocument(
                InternalReportTest.documentRequest(reportId, "notice", "10.1016/j.ijantimicag.2024.107416"), null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResultActions store(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/internal/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("report_id", reportId);
        body.put("kind", "notice");
        body.put("doi", "10.1016/j.ijantimicag.2024.107416");
        body.put("crossref_status", "ok");
        body.put("crossref_record", CROSSREF_RECORD);
        body.put("update_to_includes_paper", true);
        body.put("text_status", "ok");
        body.put("text", "This article has been retracted.");
        body.put("text_truncated", false);
        return body;
    }
}
