package com.g4t1.storage.admin;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.snapshot.SnapshotRepository;
import com.g4t1.storage.snapshot.SnapshotRequest;
import com.g4t1.storage.snapshot.SnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// POST /admin/papers/{id}/snapshots
@SpringBootTest(properties = "storage.dev-mode=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSnapshotTest {

    private static final String FETCHED_AT = "2026-09-24T12:00:00Z";

    // the retracted Lancet paper as the real APIs report it
    private static final String NOTICES = """
            [{"notice_doi": "10.1016/s0140-6736(20)31324-6", "type": "retraction", "label": "Retraction", "source": "retraction-watch", "date": "2020-06-05", "record_id": 1},
             {"notice_doi": "10.1016/s0140-6736(20)31174-0", "type": "retraction", "label": "Retraction", "source": "publisher", "date": "2020-06-05", "record_id": null},
             {"notice_doi": "10.1016/s0140-6736(20)31324-6", "type": "erratum", "label": "Erratum", "source": "publisher", "date": "2020-06-05", "record_id": null},
             {"notice_doi": "10.1016/s0140-6736(20)31249-6", "type": "correction", "label": "Correction", "source": "retraction-watch", "date": "2020-06-01", "record_id": 2}]
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    SnapshotRepository snapshots;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    private final UUID researcher = UUID.randomUUID();
    private UUID paperId;

    @BeforeEach
    void setUp() {
        Paper paper = new Paper(researcher);
        paper.setDoi("10.1016/s0140-6736(20)31180-6");
        paperId = papers.save(paper).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from reports");
        jdbc.update("delete from alerts");
        snapshots.deleteAll();
        papers.deleteAll();
    }

    @Test
    void theGivenFieldsChangeAndEverythingElseIsCopiedOntoANewRow() throws Exception {
        storeSnapshot("ok", "ok");

        edit("{\"is_retracted\": false, \"in_doaj\": false, \"cited_by_count\": 0}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.is_retracted").value(false))
                .andExpect(jsonPath("$.in_doaj").value(false))
                .andExpect(jsonPath("$.cited_by_count").value(0))
                .andExpect(jsonPath("$.doi").value("10.1016/s0140-6736(20)31180-6"))
                .andExpect(jsonPath("$.title").value("Hydroxychloroquine or chloroquine"))
                .andExpect(jsonPath("$.publication_year").value(2020))
                .andExpect(jsonPath("$.journal").value("The Lancet"))
                .andExpect(jsonPath("$.authors[0].name").value("Mandeep Mehra"))
                .andExpect(jsonPath("$.crossref_updates.length()").value(4))
                .andExpect(jsonPath("$.source_status.openalex").value("ok"))
                .andExpect(jsonPath("$.fetched_at").value(not(FETCHED_AT)));

        history()
                .andExpect(jsonPath("$.snapshots.length()").value(2))
                .andExpect(jsonPath("$.snapshots[0].is_retracted").value(true))
                .andExpect(jsonPath("$.snapshots[0].in_doaj").value(true))
                .andExpect(jsonPath("$.snapshots[0].cited_by_count").value(500))
                .andExpect(jsonPath("$.snapshots[0].fetched_at").value(FETCHED_AT))
                .andExpect(jsonPath("$.snapshots[1].is_retracted").value(false));
    }

    @Test
    void fieldsLeftOutOrNullKeepTheLatestValue() throws Exception {
        storeSnapshot("ok", "ok");

        edit("{\"is_retracted\": null, \"cited_by_count\": 7}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.is_retracted").value(true))
                .andExpect(jsonPath("$.in_doaj").value(true))
                .andExpect(jsonPath("$.cited_by_count").value(7));
    }

    @Test
    void removingTypesLeavesOutEveryEntryOfThoseTypesAndKeepsTheRest() throws Exception {
        storeSnapshot("ok", "ok");

        edit("{\"removed_crossref_update_types\": [\"retraction\", \"correction\"]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.crossref_updates[*].type").value(containsInAnyOrder("erratum")))
                .andExpect(jsonPath("$.is_retracted").value(true));
    }

    @Test
    void itCopiesTheNewestRow() throws Exception {
        storeSnapshot("ok", "ok");

        edit("{\"cited_by_count\": 1}").andExpect(status().isCreated());
        edit("{\"in_doaj\": false}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cited_by_count").value(1));
    }

    @Test
    void itWorksOnAnyResearchersPaperWithAnAdminWhoOwnsNothing() throws Exception {
        storeSnapshot("ok", "ok");

        mvc.perform(post("/admin/papers/{id}/snapshots", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_retracted\": false}"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"is_retracted\": null}",
            "{\"removed_crossref_update_types\": []}",
            "{\"cited_by_count\": -1}",
            "{\"cited_by_count\": 1.5}",
            "{\"cited_by_count\": \"3\"}",
            "{\"cited_by_count\": 3000000000}",
            "{\"is_retracted\": \"false\"}",
            "{\"in_doaj\": 0}",
            "{\"removed_crossref_update_types\": \"retraction\"}",
            "{\"removed_crossref_update_types\": [\"  \"]}",
            "{\"removed_crossref_update_types\": [null]}",
            "{\"removed_crossref_update_types\": [1]}",
            "not json"})
    void aBodyThatBreaksTheRulesIsABadRequestAndStoresNothing(String body) throws Exception {
        storeSnapshot("ok", "ok");

        edit(body).andExpect(status().isBadRequest());

        history().andExpect(jsonPath("$.snapshots.length()").value(1));
    }

    @Test
    void anIdThatIsNotAUuidIsABadRequest() throws Exception {
        mvc.perform(post("/admin/papers/not-a-uuid/snapshots")
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_retracted\": false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownPaperIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(post("/admin/papers/{id}/snapshots", unknown)
                        .header(HttpHeaders.AUTHORIZATION, admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_retracted\": false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + unknown));
    }

    @Test
    void aPaperWithNoSnapshotIsAConflict() throws Exception {
        edit("{\"is_retracted\": false}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Paper " + paperId + " has no snapshot yet; poll it once first"));
    }

    // the poll never compares a field whose source wasn't ok, so such an edit could raise nothing
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"is_retracted\": false}",
            "{\"in_doaj\": false}",
            "{\"cited_by_count\": 0}"})
    void aFieldFromOpenAlexIsAConflictWhenOpenAlexWasNotOk(String body) throws Exception {
        storeSnapshot("not_found", "ok");

        edit(body).andExpect(status().isConflict());

        history().andExpect(jsonPath("$.snapshots.length()").value(1));
    }

    @Test
    void aRemovedTypeIsAConflictWhenCrossrefWasNotOk() throws Exception {
        storeSnapshot("ok", "not_found");

        edit("{\"removed_crossref_update_types\": [\"retraction\"]}").andExpect(status().isConflict());

        history().andExpect(jsonPath("$.snapshots.length()").value(1));
    }

    // each source only stands in the way of its own fields
    @Test
    void aSourceThatWasNotOkDoesNotBlockTheOtherSourcesFields() throws Exception {
        storeSnapshot("ok", "not_found");

        edit("{\"is_retracted\": false}").andExpect(status().isCreated());
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/admin/papers/{id}/snapshots", paperId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"is_retracted\": false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userAndServiceTokensAreForbidden() throws Exception {
        storeSnapshot("ok", "ok");

        for (String token : new String[] {TestTokens.user(researcher), TestTokens.service()}) {
            mvc.perform(post("/admin/papers/{id}/snapshots", paperId)
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"is_retracted\": false}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void anAdminTokenIsNeitherAResearcherNorAService() throws Exception {
        mvc.perform(get("/papers").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/papers").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isForbidden());
    }

    private static String admin() {
        return TestTokens.admin(UUID.randomUUID());
    }

    private ResultActions edit(String body) throws Exception {
        return mvc.perform(post("/admin/papers/{id}/snapshots", paperId)
                .header(HttpHeaders.AUTHORIZATION, admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions history() throws Exception {
        return mvc.perform(get("/internal/papers/{id}/snapshots", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isOk());
    }

    private void storeSnapshot(String openalex, String crossref) {
        snapshotService.store(paperId, new SnapshotRequest(
                "10.1016/s0140-6736(20)31180-6", Instant.parse(FETCHED_AT), "W123",
                "Hydroxychloroquine or chloroquine", 2020, true, json.readTree(NOTICES), true,
                "S49861241", "journal", "The Lancet", "0140-6736", "Elsevier",
                json.readTree("[{\"name\": \"Mandeep Mehra\"}]"), 500,
                json.readTree("{\"crossref\": \"" + crossref + "\", \"openalex\": \"" + openalex
                        + "\", \"openalex_authors\": \"ok\"}")));
    }
}
