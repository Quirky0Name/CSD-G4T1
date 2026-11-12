package com.g4t1.storage.admin;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.evaluation.ResearchEvaluationClient;
import com.g4t1.storage.alert.AlertService;
import com.g4t1.storage.alert.AlertSeverity;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /admin/papers/{id}/fake-report and the public route for its PDFs
@SpringBootTest(properties = "storage.dev-mode=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FakeReportTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertService alertService;

    @MockitoSpyBean
    LocalFileStore files;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    OpenAccessPdfClient openAccess;

    @MockitoBean
    MetadataClient metadata;

    @MockitoBean
    ResearchEvaluationClient researchEvaluation;

    @Autowired
    FakeReportExample example;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;

    // no DOI and no snapshot, as a paper uploaded a moment ago might be
    @BeforeEach
    void setUp() {
        paperId = papers.save(new Paper(owner)).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        jdbc.update("delete from drafts");
        papers.deleteAll();
    }

    @Test
    void withNoStatusItIsAnAssessedReportWithTheExamplesContent() throws Exception {
        create()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.status").value("assessed"))
                .andExpect(jsonPath("$.alerts", hasSize(2)))
                .andExpect(jsonPath("$.alerts[*].change_type", containsInAnyOrder(
                        "expression_of_concern", "retraction")))
                .andExpect(jsonPath("$.documents", hasSize(4)))
                .andExpect(jsonPath("$.documents[0].kind").value("notice"))
                .andExpect(jsonPath("$.documents[0].text", startsWith("Expression of concern")))
                .andExpect(jsonPath("$.investigated_at", notNullValue()))
                .andExpect(jsonPath("$.evaluated_at", notNullValue()))
                .andExpect(jsonPath("$.evaluation", notNullValue()))
                .andExpect(jsonPath("$.recommendation", notNullValue()));
    }

    @Test
    void investigatingHasNoInvestigatedAtAndNoEvaluation() throws Exception {
        create("investigating")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("investigating"))
                .andExpect(jsonPath("$.investigated_at", nullValue()))
                .andExpect(jsonPath("$.evaluation", nullValue()))
                .andExpect(jsonPath("$.recommendation", nullValue()))
                .andExpect(jsonPath("$.evaluated_at", nullValue()))
                .andExpect(jsonPath("$.alerts", hasSize(2)))
                .andExpect(jsonPath("$.documents", hasSize(4)));
    }

    @Test
    void investigatedHasAnInvestigatedAtAndNoEvaluation() throws Exception {
        create("investigated")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("investigated"))
                .andExpect(jsonPath("$.investigated_at", notNullValue()))
                .andExpect(jsonPath("$.evaluation", nullValue()))
                .andExpect(jsonPath("$.evaluated_at", nullValue()));
    }

    @Test
    void assessedStoresWhatTheExampleRecordsAndSaysItIsFake() throws Exception {
        create("assessed").andExpect(status().isCreated());

        // the fake report's and its past's
        List<String> assessments = jdbc.queryForList("select assessment from reports where paper_id = ?",
                String.class, paperId);
        assertThat(assessments).hasSize(2).allSatisfy(assessment -> {
            assertThat(JsonPath.<Boolean>read(assessment, "$.fake")).isTrue();
            assertThat(JsonPath.<Object>read(assessment, "$.change")).isNotNull();
        });
    }

    @Test
    void assessedAlsoStoresAnEarlierAssessedReportListedAfterTheFakeOne() throws Exception {
        long fakeId = id(create("assessed").andExpect(status().isCreated()));

        ownerReports()
                .andExpect(jsonPath("$.reports", hasSize(2)))
                .andExpect(jsonPath("$.reports[0].id").value(fakeId))
                .andExpect(jsonPath("$.reports[*].status", everyItem(is("assessed"))))
                .andExpect(jsonPath("$.reports[1].alerts", hasSize(1)))
                .andExpect(jsonPath("$.reports[1].alerts[0].change_type").value("correction"))
                .andExpect(jsonPath("$.reports[1].documents[*].kind", containsInAnyOrder(
                        "notice", "new_version", "current_version", "related")))
                .andExpect(jsonPath("$.reports[1].change_severity").value("low"))
                .andExpect(jsonPath("$.reports[1].impact_level").value("medium"))
                .andExpect(jsonPath("$.reports[1].context_chunks", nullValue()));
    }

    @Test
    void thePastIsDatedThirtyDaysBeforeTheCallAndCarriesTheExamplesFeedback() throws Exception {
        Instant before = Instant.now();
        create("assessed").andExpect(status().isCreated());
        Instant after = Instant.now();

        String past = body(ownerReports());
        List<String> dates = new ArrayList<>();
        for (String path : List.of("$.reports[1].created_at", "$.reports[1].investigated_at",
                "$.reports[1].evaluated_at", "$.reports[1].feedback.updated_at")) {
            dates.add(JsonPath.read(past, path));
        }
        dates.addAll(JsonPath.read(past, "$.reports[1].alerts[*].detected_at"));
        dates.addAll(JsonPath.read(past, "$.reports[1].documents[*].created_at"));
        assertThat(dates).hasSize(9).allSatisfy(date -> assertThat(Instant.parse(date))
                .isBetween(before.minus(30, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES),
                        after.minus(30, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES)));
        assertThat(JsonPath.<String>read(past, "$.reports[1].feedback.verdict")).isEqualTo("overstated");
        assertThat(JsonPath.<String>read(past, "$.reports[1].feedback.note"))
                .isEqualTo(example.pastContent().feedback().note());
    }

    @Test
    void theFakeReportsContextNamesThePastItsFeedbackAndTheExampleProfile() throws Exception {
        Paper titled = new Paper(owner);
        titled.setTitle(FakeReportExample.PAPER_TITLE);
        paperId = papers.save(titled).getId();
        create("assessed").andExpect(status().isCreated());

        String reports = body(ownerReports());
        int pastId = JsonPath.read(reports, "$.reports[1].id");
        List<String> pastAlerts = ids(reports, "$.reports[1].alerts[*].id");
        List<String> pastDocuments = ids(reports, "$.reports[1].documents[*].id");
        List<Map<String, Object>> items = JsonPath.read(reports, "$.reports[0].context_chunks");

        assertThat(items).extracting(item -> item.get("source_type")).containsExactly(
                "document", "alert", "document", "report", "document", "feedback", "profile");
        for (Map<String, Object> item : items.subList(0, 5)) {
            String sourceId = (String) item.get("source_id");
            switch ((String) item.get("source_type")) {
                case "alert" -> assertThat(pastAlerts).contains(sourceId);
                case "document" -> assertThat(pastDocuments).contains(sourceId);
                default -> assertThat(sourceId).isEqualTo(String.valueOf(pastId));
            }
        }
        Map<String, Object> notice = items.get(2);
        assertThat(notice).containsEntry("page", null).containsKeys("start", "end");
        assertThat(items.getFirst()).containsEntry("page", 2);

        Map<String, Object> feedback = items.get(5);
        assertThat(feedback).containsEntry("source_id", String.valueOf(pastId)).containsEntry("page", null)
                .containsEntry("paper_id", paperId.toString())
                .containsEntry("paper_title", FakeReportExample.PAPER_TITLE)
                .containsEntry("source_date", JsonPath.<String>read(reports, "$.reports[1].feedback.updated_at"))
                .containsEntry("verdict", "overstated");

        Map<String, Object> profile = items.get(6);
        assertThat(profile).containsEntry("source_id", "example").containsEntry("page", null);
        FakeReportExample.ExampleProfile expected = example.pastContent().profile();
        assertThat(JsonPath.<Map<String, Object>>read(reports, "$.reports[0].context_chunks[6].profile"))
                .containsEntry("occupation", expected.occupation())
                .containsEntry("occupation_other", expected.occupationOther())
                .containsEntry("research_fields", expected.researchFields())
                .containsEntry("research_interests", expected.researchInterests())
                .containsEntry("work_types", expected.workTypes())
                .containsEntry("response_preferences", expected.responsePreferences());
    }

    @ParameterizedTest
    @ValueSource(strings = {"investigating", "investigated"})
    void beforeAssessmentOnlyTheFakeReportIsStoredWithNoContext(String reportStatus) throws Exception {
        create(reportStatus).andExpect(status().isCreated()).andExpect(jsonPath("$.context_chunks", nullValue()));

        ownerReports().andExpect(jsonPath("$.reports", hasSize(1)))
                .andExpect(jsonPath("$.reports[0].context_chunks", nullValue()));
    }

    @Test
    void thePastIsNeitherEmbeddedNorSentToResearchEvaluation() throws Exception {
        create("assessed").andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select count(*) from chunks", Integer.class)).isZero();
        verifyNoInteractions(researchEvaluation);
    }

    @Test
    void theOwnerSeesTheAlertsAndTheReports() throws Exception {
        create().andExpect(status().isCreated());

        mvc.perform(get("/papers/{id}/alerts", paperId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts", hasSize(3)))
                .andExpect(jsonPath("$.alerts[*].status", everyItem(is("new"))));
        mvc.perform(get("/papers/{id}/reports", paperId).header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports", hasSize(2)))
                .andExpect(jsonPath("$.reports[0].status").value("assessed"))
                .andExpect(jsonPath("$.reports[0].alerts", hasSize(2)));
    }

    @Test
    void twoCallsGiveTwoReportsAndTwoPastsWithTheirOwnAlertsDocumentsAndFiles() throws Exception {
        create().andExpect(status().isCreated());
        create().andExpect(status().isCreated());

        // newest first: the two fake reports, then the two pasts dated before them
        admin(get("/admin/papers/{id}/reports", paperId))
                .andExpect(jsonPath("$.reports", hasSize(4)))
                .andExpect(jsonPath("$.reports[0].alerts", hasSize(2)))
                .andExpect(jsonPath("$.reports[1].alerts", hasSize(2)))
                .andExpect(jsonPath("$.reports[2].alerts", hasSize(1)))
                .andExpect(jsonPath("$.reports[3].alerts", hasSize(1)))
                .andExpect(jsonPath("$.reports[0].documents", hasSize(4)))
                .andExpect(jsonPath("$.reports[1].documents", hasSize(4)))
                .andExpect(jsonPath("$.reports[2].documents", hasSize(4)))
                .andExpect(jsonPath("$.reports[3].documents", hasSize(4)));
        assertThat(jdbc.queryForObject("select count(*) from alerts where paper_id = ?", Integer.class, paperId))
                .isEqualTo(6);
        // each call: the fake report's 2 PDFs and the past's 3
        assertThat(fileKeys()).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void itsAlertsHaveChangeKeysOfTheirOwn() throws Exception {
        create().andExpect(status().isCreated());

        mvc.perform(get("/internal/papers/{id}/alerts/change-keys", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(jsonPath("$.change_keys", hasSize(3)))
                .andExpect(jsonPath("$.change_keys", everyItem(startsWith("fake:"))));
    }

    @Test
    void theDocumentsThatHavePdfsAreStoredAndLinkedThroughThePublicRoute() throws Exception {
        String body = create().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        List<Integer> notices = JsonPath.read(body, "$.documents[?(@.kind == 'notice')].id");
        assertThat(notices).hasSize(2);
        assertThat(JsonPath.<List<String>>read(body, "$.documents[?(@.kind == 'notice')].pdf_status"))
                .containsOnly("skipped");
        for (Integer notice : notices) {
            mvc.perform(get("/internal/documents/{id}/pdf", notice)
                            .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                    .andExpect(status().isNotFound());
        }
        for (String[] kind : new String[][] {{"new_version", "new-version.pdf"}, {"current_version", "current-version.pdf"}}) {
            String path = "$.documents[?(@.kind == '" + kind[0] + "')]";
            assertThat(JsonPath.<List<String>>read(body, path + ".pdf_status")).containsExactly("ok");
            assertThat(JsonPath.<List<String>>read(body, path + ".pdf_source_url"))
                    .containsExactly("/api/public/fake-report/" + kind[1]);
            assertThat(JsonPath.<List<String>>read(body, path + ".pdf_fetched_at")).hasSize(1).doesNotContainNull();
            int id = JsonPath.<List<Integer>>read(body, path + ".id").getFirst();
            mvc.perform(get("/internal/documents/{id}/pdf", id)
                            .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(example(kind[1])));
        }
        assertThat(jdbc.queryForList("select sha256 from report_documents where file_key is not null and report_id = ?",
                String.class, JsonPath.<Integer>read(body, "$.id"))).hasSize(2).doesNotContainNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"new-version.pdf", "current-version.pdf", "paper.pdf", "draft.pdf",
            "past-new-version.pdf", "past-related.pdf"})
    void theExamplePdfsAreServedWithNoToken(String file) throws Exception {
        byte[] pdf = mvc.perform(get("/public/fake-report/{file}", file))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(example(file)))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.json", "past.json", "unknown.pdf", "..", "..%2Fpaper.pdf", "..\\paper.pdf"})
    void nothingElseIsServed(String file) throws Exception {
        mvc.perform(get("/public/fake-report/{file}", file)).andExpect(status().isNotFound());
    }

    @Test
    void aPathOutOfTheFolderIsNotFound() throws Exception {
        mvc.perform(get(URI.create("/public/fake-report/../application.yml"))).andExpect(status().isNotFound());
    }

    @Test
    void theProjectsDraftIsUnchanged() throws Exception {
        byte[] draft = "%PDF-1.7 the researcher's own draft".getBytes(StandardCharsets.US_ASCII);
        mvc.perform(multipart("/draft").file(new MockMultipartFile("file", "draft.pdf", "application/pdf", draft))
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isCreated());

        create().andExpect(status().isCreated());

        mvc.perform(get("/internal/papers/{id}/draft", paperId).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(draft));
    }

    @Test
    void anAlertWaitingForItsReportStaysOutOfIt() throws Exception {
        long waiting = alertService.store(paperId, new NewAlertRequest(ChangeType.RETRACTION, "retraction",
                AlertSeverity.HIGH, "description", "recommendation", null,
                Instant.parse("2026-09-24T12:00:00Z"), 1L, 0L)).alert().id();

        create()
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alerts", hasSize(2)))
                .andExpect(jsonPath("$.alerts[*].id", not(hasItem((int) waiting))));

        assertThat(jdbc.queryForObject("select report_id from alerts where id = ?", Long.class, waiting)).isNull();
    }

    @Test
    void itMakesNoOutboundCall() throws Exception {
        create().andExpect(status().isCreated());

        verifyNoInteractions(openAccess, metadata);
    }

    @Test
    void anUnknownStatusIsABadRequestAndStoresNothing() throws Exception {
        create("published").andExpect(status().isBadRequest());

        admin(get("/admin/papers/{id}/reports", paperId)).andExpect(jsonPath("$.reports", hasSize(0)));
        admin(get("/admin/papers/{id}/alerts", paperId)).andExpect(jsonPath("$.alerts", hasSize(0)));
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        admin(post("/admin/papers/not-a-uuid/fake-report")).andExpect(status().isBadRequest());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/admin/papers/{id}/fake-report", paperId)).andExpect(status().isUnauthorized());
    }

    @Test
    void userAndServiceTokensAreForbidden() throws Exception {
        mvc.perform(post("/admin/papers/{id}/fake-report", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/papers/{id}/fake-report", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownPaperIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        admin(post("/admin/papers/{id}/fake-report", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + unknown));
    }

    @Test
    void clearingHistoryRemovesTheReportsTheirAlertsDocumentsAndFiles() throws Exception {
        create().andExpect(status().isCreated());
        List<String> keys = fileKeys();
        assertThat(keys).hasSize(5);
        assertThat(files.read(keys.getFirst())).isPresent();

        admin(delete("/admin/papers/{id}/history", paperId)).andExpect(status().isNoContent());

        admin(get("/admin/papers/{id}/reports", paperId)).andExpect(jsonPath("$.reports", hasSize(0)));
        admin(get("/admin/papers/{id}/alerts", paperId)).andExpect(jsonPath("$.alerts", hasSize(0)));
        assertThat(jdbc.queryForObject("select count(*) from report_documents", Integer.class)).isZero();
        assertThat(keys).allSatisfy(key -> assertThat(files.read(key)).isEmpty());
    }

    // 1: a file of the past fails; 3: the past is stored and the fake report's first file fails
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void aFailureStoresNothingAndLeavesNoFileBehind(int filesBeforeTheFailure) throws Exception {
        List<String> saved = new ArrayList<>();
        doAnswer(call -> {
            if (saved.size() == filesBeforeTheFailure) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "disk full");
            }
            String key = (String) call.callRealMethod();
            saved.add(key);
            return key;
        }).when(files).save(any());

        create().andExpect(status().isInternalServerError());

        assertThat(saved).hasSize(filesBeforeTheFailure)
                .allSatisfy(key -> assertThat(files.read(key)).isEmpty());
        admin(get("/admin/papers/{id}/reports", paperId)).andExpect(jsonPath("$.reports", hasSize(0)));
        admin(get("/admin/papers/{id}/alerts", paperId)).andExpect(jsonPath("$.alerts", hasSize(0)));
    }

    private ResultActions ownerReports() throws Exception {
        return mvc.perform(get("/papers/{id}/reports", paperId)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner))).andExpect(status().isOk());
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static long id(ResultActions result) throws Exception {
        return JsonPath.<Integer>read(body(result), "$.id");
    }

    private static List<String> ids(String json, String path) {
        return JsonPath.<List<Integer>>read(json, path).stream().map(String::valueOf).toList();
    }

    private ResultActions create() throws Exception {
        return admin(post("/admin/papers/{id}/fake-report", paperId));
    }

    private ResultActions create(String reportStatus) throws Exception {
        return admin(post("/admin/papers/{id}/fake-report", paperId).param("status", reportStatus));
    }

    private ResultActions admin(MockHttpServletRequestBuilder call) throws Exception {
        return mvc.perform(call.header(HttpHeaders.AUTHORIZATION, TestTokens.admin(UUID.randomUUID())));
    }

    private List<String> fileKeys() {
        return jdbc.queryForList("select file_key from report_documents where file_key is not null", String.class);
    }

    private static byte[] example(String file) throws IOException {
        return new ClassPathResource("dev/fake-report/" + file).getContentAsByteArray();
    }
}
