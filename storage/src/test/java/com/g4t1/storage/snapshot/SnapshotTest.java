package com.g4t1.storage.snapshot;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.metadata.MetadataClient;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SnapshotTest {

    // what Updating's Snapshot.model_dump(mode="json") sends, nulls included
    private static final String SNAPSHOT = """
            {"doi": "10.1016/s0140-6736(20)31180-6", "fetched_at": "2026-09-24T12:00:00Z",
             "openalex_id": "W3027680906", "title": "Hydroxychloroquine or chloroquine", "publication_year": 2020,
             "is_retracted": true,
             "crossref_updates": [{"notice_doi": "10.1016/s0140-6736(20)31324-6", "type": "retraction",
                                   "label": "Retraction", "source": "publisher", "date": "2020-06-05", "record_id": null}],
             "in_doaj": null, "journal_source_id": "S49861241", "journal_source_type": "journal",
             "journal": "The Lancet", "issn_l": "0140-6736", "publisher": "Elsevier BV",
             "authors": [{"name": "Mandeep R. Mehra", "openalex_author_id": "A1", "position": "first",
                          "institutions": ["Brigham and Women's Hospital"], "h_index": 90, "works_count": 1200}],
             "cited_by_count": 1252,
             "source_status": {"crossref": "ok", "openalex": "ok", "openalex_authors": "error"}}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    SnapshotRepository snapshots;

    @Autowired
    JsonMapper json;

    @MockitoBean
    GrobidClient grobid;

    @MockitoBean
    MetadataClient metadata;

    private UUID paperId;

    @BeforeEach
    void setUp() {
        snapshots.deleteAll();
        papers.deleteAll();
        Paper paper = new Paper(UUID.randomUUID());
        paper.setDoi("10.1016/s0140-6736(20)31180-6");
        paperId = papers.save(paper).getId();
    }

    @Test
    void storedSnapshotComesBackExactlyAsSentPlusItsIds() throws Exception {
        String stored = store(paperId, SNAPSHOT)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ObjectNode expected = (ObjectNode) json.readTree(SNAPSHOT);
        ObjectNode actual = (ObjectNode) json.readTree(stored);
        assertThat(actual.get("snapshot_id").asLong()).isPositive();
        assertThat(actual.get("paper_id").asString()).isEqualTo(paperId.toString());
        actual.remove("snapshot_id");
        actual.remove("paper_id");
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void historyIsOldestFirstAfterTheWatermarkUpToTheLimit() throws Exception {
        long first = snapshotId(store(paperId, SNAPSHOT));
        long second = snapshotId(store(paperId, SNAPSHOT.replace("2026-09-24", "2026-09-25")));
        long third = snapshotId(store(paperId, SNAPSHOT.replace("2026-09-24", "2026-09-26")));

        history("").andExpect(jsonPath("$.snapshots[*].snapshot_id").value(
                org.hamcrest.Matchers.contains((int) first, (int) second, (int) third)));
        history("?after_id=" + first + "&limit=1")
                .andExpect(jsonPath("$.snapshots.length()").value(1))
                .andExpect(jsonPath("$.snapshots[0].snapshot_id").value(second))
                .andExpect(jsonPath("$.snapshots[0].fetched_at").value("2026-09-25T12:00:00Z"));
        history("?after_id=" + third).andExpect(jsonPath("$.snapshots.length()").value(0));
    }

    @Test
    void historyRowsKeepEveryFieldIncludingNulls() throws Exception {
        store(paperId, SNAPSHOT);

        // hasJsonPath, not exists: exists treats a null value as missing
        history("").andExpect(jsonPath("$.snapshots[0].in_doaj").hasJsonPath())
                .andExpect(jsonPath("$.snapshots[0].in_doaj").isEmpty())
                .andExpect(jsonPath("$.snapshots[0].crossref_updates[0].record_id").isEmpty())
                .andExpect(jsonPath("$.snapshots[0].authors[0].institutions[0]").value("Brigham and Women's Hospital"))
                .andExpect(jsonPath("$.snapshots[0].source_status.openalex_authors").value("error"));
    }

    @Test
    void nullListsStayNull() throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(SNAPSHOT);
        body.putNull("crossref_updates");
        body.putNull("authors");

        store(paperId, body.toString()).andExpect(status().isCreated());

        history("").andExpect(jsonPath("$.snapshots[0].crossref_updates").isEmpty())
                .andExpect(jsonPath("$.snapshots[0].authors").isEmpty());
        assertThat(snapshots.findAll().getFirst().getAuthors()).isNull();
    }

    @Test
    void unknownPaperIsA404() throws Exception {
        UUID missing = UUID.randomUUID();

        store(missing, SNAPSHOT)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper with id " + missing));
        mvc.perform(get("/internal/papers/{id}/background-info/history", missing)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isNotFound());
        assertThat(snapshots.count()).isZero();
    }

    @Test
    void snapshotWithoutFetchedAtIsRejected() throws Exception {
        ObjectNode body = (ObjectNode) json.readTree(SNAPSHOT);
        body.remove("fetched_at");

        store(paperId, body.toString()).andExpect(status().isBadRequest());
        assertThat(snapshots.count()).isZero();
    }

    @Test
    void userTokensAreRefused() throws Exception {
        mvc.perform(post("/internal/papers/{id}/background-info", paperId)
                        .contentType(MediaType.APPLICATION_JSON).content(SNAPSHOT)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/papers/{id}/background-info/history", paperId)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    private ResultActions store(UUID paper, String body) throws Exception {
        return mvc.perform(post("/internal/papers/{id}/background-info", paper)
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private ResultActions history(String query) throws Exception {
        return mvc.perform(get("/internal/papers/" + paperId + "/background-info/history" + query)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private long snapshotId(ResultActions stored) throws Exception {
        JsonNode node = json.readTree(stored.andReturn().getResponse().getContentAsString());
        return node.get("snapshot_id").asLong();
    }
}
