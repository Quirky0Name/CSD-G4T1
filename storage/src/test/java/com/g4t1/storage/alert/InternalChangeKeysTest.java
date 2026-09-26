package com.g4t1.storage.alert;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalChangeKeysTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertRepository alerts;

    private UUID paperId;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(UUID.randomUUID())).getId();
    }

    @Test
    void everyStoredChangeKeyOfThePaperIsListedWhateverItsStatus() throws Exception {
        seed(paperId, "retraction", AlertStatus.NEW);
        seed(paperId, "correction:10.1/c", AlertStatus.ACKNOWLEDGED);
        // a dismissed alert is still a change Research Evaluation has evaluated
        seed(paperId, "erratum:10.1/e", AlertStatus.DISMISSED);

        keys(paperId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change_keys").value(contains("correction:10.1/c", "erratum:10.1/e", "retraction")));
    }

    @Test
    void anotherPapersKeysAreNotListed() throws Exception {
        UUID otherPaper = papers.save(new Paper(UUID.randomUUID())).getId();
        seed(otherPaper, "retraction", AlertStatus.NEW);
        seed(otherPaper, "doaj_delisting:7", AlertStatus.NEW);
        seed(paperId, "correction:10.1/c", AlertStatus.NEW);

        keys(paperId).andExpect(jsonPath("$.change_keys").value(contains("correction:10.1/c")));
    }

    @Test
    void aPaperWithNoAlertsGivesAnEmptyList() throws Exception {
        keys(paperId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.change_keys").isArray())
                .andExpect(jsonPath("$.change_keys", hasSize(0)));
    }

    @Test
    void anUnknownPaperIsTheNoPaper404() throws Exception {
        UUID missing = UUID.randomUUID();

        keys(missing)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + missing));
    }

    @Test
    void aPaperIdThatIsNotAUuidIsABadRequest() throws Exception {
        mvc.perform(get("/internal/papers/not-a-uuid/alerts/change-keys")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listingKeysNeedsAServiceToken() throws Exception {
        String url = "/internal/papers/" + paperId + "/alerts/change-keys";

        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    private ResultActions keys(UUID paper) throws Exception {
        return mvc.perform(get("/internal/papers/" + paper + "/alerts/change-keys")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private void seed(UUID paper, String changeKey, AlertStatus status) {
        String type = changeKey.split(":")[0];
        Alert alert = new Alert(paper, new NewAlertRequest(ChangeType.of(type), changeKey, Severity.MEDIUM,
                "Description of " + type, "Recommendation for " + type, null,
                Instant.parse("2026-09-20T12:00:00Z"), 42L, 41L));
        alert.setStatus(status);
        alerts.save(alert);
    }
}
