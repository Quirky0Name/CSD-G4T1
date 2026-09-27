package com.g4t1.storage.alert;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertNoteTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertRepository alerts;

    @Autowired
    AlertNoteRepository notes;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;
    private long alertId;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(owner)).getId();
        alertId = seed("retraction").getId();
    }

    @Test
    void notesComeBackNewestFirstWithTheTimeTheyWereAdded() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        String first = add(alertId, owner, "Read the retraction notice.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Read the retraction notice."))
                .andExpect(jsonPath("$.created_at").isString())
                .andReturn().getResponse().getContentAsString();
        add(alertId, owner, "Removed the citation from my draft.").andExpect(status().isCreated());
        add(alertId, owner, "Emailed my co-author.").andExpect(status().isCreated());

        list(alertId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[*].text").value(contains(
                        "Emailed my co-author.", "Removed the citation from my draft.", "Read the retraction notice.")))
                .andExpect(jsonPath("$.notes[2].created_at").value(JsonPath.<String>read(first, "$.created_at")));
        assertThat(notes.findAll()).allSatisfy(note -> {
            assertThat(note.getAlertId()).isEqualTo(alertId);
            assertThat(note.getCreatedAt()).isBetween(before, Instant.now());
        });
    }

    @Test
    void eachAlertShowsOnlyItsOwnNotes() throws Exception {
        long erratum = seed("erratum:10.1/e").getId();

        add(alertId, owner, "About the retraction.").andExpect(status().isCreated());
        add(erratum, owner, "About the erratum.").andExpect(status().isCreated());

        list(alertId, owner).andExpect(jsonPath("$.notes[*].text").value(contains("About the retraction.")));
        list(erratum, owner).andExpect(jsonPath("$.notes[*].text").value(contains("About the erratum.")));
    }

    @Test
    void anAlertWithNoNotesGivesAnEmptyList() throws Exception {
        list(alertId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").isArray())
                .andExpect(jsonPath("$.notes", hasSize(0)));
    }

    @Test
    void aNoteCanBeUpTo2000Characters() throws Exception {
        add(alertId, owner, "a".repeat(2000)).andExpect(status().isCreated());
        add(alertId, owner, "b".repeat(2001)).andExpect(status().isBadRequest());

        assertThat(notes.findAll()).extracting(AlertNote::getText).containsExactly("a".repeat(2000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"text\": \"\"}", "{\"text\": \"   \"}", "{\"text\": null}", "{}", "not json"})
    void aMissingOrBlankNoteIsABadRequestAndStoresNothing(String body) throws Exception {
        mvc.perform(post("/alerts/" + alertId + "/notes").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());

        assertThat(notes.count()).isZero();
    }

    @Test
    void anotherUsersAlertLooksTheSameAsAMissingOne() throws Exception {
        long missing = alertId + 1000;
        UUID stranger = UUID.randomUUID();

        add(alertId, stranger, "Not my alert.")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No alert " + alertId));
        add(missing, owner, "No such alert.")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No alert " + missing));
        list(alertId, stranger)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No alert " + alertId))
                .andExpect(jsonPath("$.notes").doesNotExist());
        list(missing, owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No alert " + missing));

        assertThat(notes.count()).isZero();
    }

    @Test
    void aNoteLeavesTheAlertsStatusAlone() throws Exception {
        Instant dismissedAt = Instant.parse("2026-09-21T08:00:00Z");
        Alert alert = alerts.findById(alertId).orElseThrow();
        alert.setStatus(AlertStatus.DISMISSED);
        alert.setStatusChangedAt(dismissedAt);
        alerts.save(alert);

        add(alertId, owner, "Not relevant to my review.").andExpect(status().isCreated());

        Alert stored = alerts.findById(alertId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AlertStatus.DISMISSED);
        assertThat(stored.getStatusChangedAt()).isEqualTo(dismissedAt);
        list(alertId, owner).andExpect(jsonPath("$.notes[*].text").value(contains("Not relevant to my review.")));
    }

    @Test
    void notesAreDeletedWithTheirPaper() throws Exception {
        add(alertId, owner, "Read the retraction notice.").andExpect(status().isCreated());
        assertThat(notes.count()).isEqualTo(1);

        papers.deleteById(paperId);

        assertThat(alerts.count()).isZero();
        assertThat(notes.count()).isZero();
    }

    @Test
    void anIdThatIsNotANumberIsABadRequest() throws Exception {
        mvc.perform(get("/alerts/abc/notes").header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/alerts/abc/notes").contentType(MediaType.APPLICATION_JSON).content("{\"text\": \"x\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notesNeedAUserToken() throws Exception {
        String url = "/alerts/" + alertId + "/notes";
        String body = "{\"text\": \"A note.\"}";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());

        assertThat(notes.count()).isZero();
    }

    private ResultActions add(long alert, UUID user, String text) throws Exception {
        return mvc.perform(post("/alerts/" + alert + "/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"" + text + "\"}")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions list(long alert, UUID user) throws Exception {
        return mvc.perform(get("/alerts/" + alert + "/notes").header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private Alert seed(String changeKey) {
        String type = changeKey.split(":")[0];
        return alerts.save(new Alert(paperId, new NewAlertRequest(ChangeType.of(type), changeKey, Severity.HIGH,
                "Description of " + type, "Recommendation for " + type, null,
                Instant.parse("2026-09-20T12:00:00Z"), 42L, 41L)));
    }
}
