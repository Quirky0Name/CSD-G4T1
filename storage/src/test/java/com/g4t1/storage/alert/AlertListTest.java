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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertListTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertRepository alerts;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(owner)).getId();
    }

    @Test
    void alertsComeNewestFirstWithTheIdBreakingTies() throws Exception {
        long oldest = seed(paperId, "correction:a", "2026-09-01T00:00:00Z", AlertStatus.NEW).getId();
        long newest = seed(paperId, "retraction", "2026-09-20T00:00:00Z", AlertStatus.NEW).getId();
        // two changes found in the same snapshot pair share a detection time
        long tiedFirst = seed(paperId, "correction:b", "2026-09-10T00:00:00Z", AlertStatus.NEW).getId();
        long tiedSecond = seed(paperId, "erratum:c", "2026-09-10T00:00:00Z", AlertStatus.NEW).getId();

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].id").value(contains(
                        (int) newest, (int) tiedSecond, (int) tiedFirst, (int) oldest)));
    }

    @Test
    void alertsFromOnePollComeMostSevereFirst() throws Exception {
        // stored in the order Research Evaluation detects them, so the retraction has the lowest id
        String poll = "2026-09-10T00:00:00Z";
        long retraction = seed(paperId, "retraction", poll, Severity.HIGH, AlertStatus.NEW).getId();
        long erratum = seed(paperId, "erratum:a", poll, Severity.LOW, AlertStatus.NEW).getId();
        long correction = seed(paperId, "correction:b", poll, Severity.MEDIUM, AlertStatus.NEW).getId();
        long concern = seed(paperId, "expression_of_concern:c", poll, Severity.MEDIUM, AlertStatus.NEW).getId();
        // a later poll still comes first, whatever its severity
        long later = seed(paperId, "erratum:d", "2026-09-11T00:00:00Z", Severity.LOW, AlertStatus.NEW).getId();

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].id").value(contains(
                        (int) later, (int) retraction, (int) concern, (int) correction, (int) erratum)));
    }

    @Test
    void eachAlertHasItsFieldsAndNotItsInternalColumns() throws Exception {
        Alert alert = seed(paperId, "retraction", "2026-09-20T12:00:00Z", AlertStatus.NEW);

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts", hasSize(1)))
                .andExpect(jsonPath("$.alerts[0].id").value(alert.getId()))
                .andExpect(jsonPath("$.alerts[0].paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.alerts[0].change_type").value("retraction"))
                .andExpect(jsonPath("$.alerts[0].severity").value("high"))
                .andExpect(jsonPath("$.alerts[0].description").value("Description of retraction"))
                .andExpect(jsonPath("$.alerts[0].recommendation").value("Recommendation for retraction"))
                .andExpect(jsonPath("$.alerts[0].notice_doi").value("10.1/notice"))
                .andExpect(jsonPath("$.alerts[0].detected_at").value("2026-09-20T12:00:00Z"))
                .andExpect(jsonPath("$.alerts[0].status").value("new"))
                .andExpect(jsonPath("$.alerts[0].status_changed_at").value(nullValue()))
                .andExpect(jsonPath("$.alerts[0].change_key").doesNotExist())
                .andExpect(jsonPath("$.alerts[0].snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.alerts[0].previous_snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.alerts[0].created_at").doesNotExist());
    }

    @Test
    void aPaperWithNoAlertsGivesAnEmptyList() throws Exception {
        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts").isArray())
                .andExpect(jsonPath("$.alerts", hasSize(0)));
    }

    @Test
    void onlyThisPapersAlertsAreListed() throws Exception {
        UUID otherPaper = papers.save(new Paper(owner)).getId();
        seed(otherPaper, "retraction", "2026-09-20T00:00:00Z", AlertStatus.NEW);
        long mine = seed(paperId, "retraction", "2026-09-19T00:00:00Z", AlertStatus.NEW).getId();

        list(paperId, owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) mine)));
    }

    @Test
    void dismissedAlertsAreHiddenUnlessAskedFor() throws Exception {
        long acknowledged = seed(paperId, "correction:a", "2026-09-01T00:00:00Z", AlertStatus.ACKNOWLEDGED).getId();
        long dismissed = seed(paperId, "erratum:b", "2026-09-02T00:00:00Z", AlertStatus.DISMISSED).getId();
        long fresh = seed(paperId, "retraction", "2026-09-03T00:00:00Z", AlertStatus.NEW).getId();

        list(paperId, owner)
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) fresh, (int) acknowledged)))
                .andExpect(jsonPath("$.alerts[1].status").value("acknowledged"));
        mvc.perform(get("/papers/" + paperId + "/alerts").param("include_dismissed", "false")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) fresh, (int) acknowledged)));
        mvc.perform(get("/papers/" + paperId + "/alerts").param("include_dismissed", "true")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alerts[*].id").value(contains((int) fresh, (int) dismissed, (int) acknowledged)))
                .andExpect(jsonPath("$.alerts[1].status").value("dismissed"));
    }

    @Test
    void onlyDismissedAlertsGiveAnEmptyListByDefault() throws Exception {
        seed(paperId, "retraction", "2026-09-03T00:00:00Z", AlertStatus.DISMISSED);

        list(paperId, owner).andExpect(jsonPath("$.alerts", hasSize(0)));
    }

    @Test
    void anotherUsersPaperLooksTheSameAsAMissingOne() throws Exception {
        seed(paperId, "retraction", "2026-09-20T00:00:00Z", AlertStatus.NEW);
        UUID missing = UUID.randomUUID();

        list(paperId, UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + paperId))
                .andExpect(jsonPath("$.alerts").doesNotExist());
        list(missing, owner)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + missing));
    }

    @Test
    void aBadIncludeDismissedValueIsABadRequest() throws Exception {
        mvc.perform(get("/papers/" + paperId + "/alerts").param("include_dismissed", "maybe")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listingNeedsAUserToken() throws Exception {
        String url = "/papers/" + paperId + "/alerts";

        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());
    }

    private ResultActions list(UUID paper, UUID user) throws Exception {
        return mvc.perform(get("/papers/" + paper + "/alerts").header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private Alert seed(UUID paper, String changeKey, String detectedAt, AlertStatus status) {
        return seed(paper, changeKey, detectedAt, Severity.HIGH, status);
    }

    private Alert seed(UUID paper, String changeKey, String detectedAt, Severity severity, AlertStatus status) {
        String type = changeKey.split(":")[0];
        Alert alert = new Alert(paper, new NewAlertRequest(ChangeType.of(type), changeKey, severity,
                "Description of " + type, "Recommendation for " + type, "10.1/notice",
                Instant.parse(detectedAt), 42L, 41L));
        if (status != AlertStatus.NEW) {
            alert.setStatus(status);
            alert.setStatusChangedAt(Instant.parse(detectedAt).plusSeconds(60));
        }
        return alerts.save(alert);
    }
}
