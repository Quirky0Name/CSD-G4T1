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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertActionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertRepository alerts;

    private final UUID owner = UUID.randomUUID();
    private UUID paperId;
    private long alertId;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(owner)).getId();
        alertId = alerts.save(new Alert(paperId, new NewAlertRequest(ChangeType.RETRACTION, "retraction",
                Severity.HIGH, "This paper was retracted.", "Stop relying on its findings.", "10.1/notice",
                Instant.parse("2026-09-20T12:00:00Z"), 42L, 41L))).getId();
    }

    @Test
    void acknowledgingSetsTheStatusAndWhenItChanged() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        change(alertId, owner, "acknowledged")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alertId))
                .andExpect(jsonPath("$.paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.change_type").value("retraction"))
                .andExpect(jsonPath("$.description").value("This paper was retracted."))
                .andExpect(jsonPath("$.status").value("acknowledged"))
                .andExpect(jsonPath("$.status_changed_at").isString())
                .andExpect(jsonPath("$.change_key").doesNotExist());

        Alert saved = stored();
        assertThat(saved.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(saved.getStatusChangedAt()).isBetween(before, Instant.now());
    }

    @Test
    void theResponseShowsTheSameChangeTimeALaterReadDoes() throws Exception {
        String patched = change(alertId, owner, "acknowledged").andReturn().getResponse().getContentAsString();
        String changedAt = JsonPath.read(patched, "$.status_changed_at");

        list(false).andExpect(jsonPath("$.alerts[0].status_changed_at").value(changedAt));
    }

    @Test
    void aDismissedAlertDropsOutOfTheListButCanStillBeAskedFor() throws Exception {
        change(alertId, owner, "dismissed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dismissed"));

        assertThat(stored().getStatus()).isEqualTo(AlertStatus.DISMISSED);
        list(false).andExpect(jsonPath("$.alerts", hasSize(0)));
        list(true).andExpect(jsonPath("$.alerts[*].id").value(contains((int) alertId)));
    }

    @Test
    void eitherStatusCanReplaceTheOther() throws Exception {
        change(alertId, owner, "acknowledged").andExpect(status().isOk());
        change(alertId, owner, "dismissed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("dismissed"));
        assertThat(stored().getStatus()).isEqualTo(AlertStatus.DISMISSED);

        change(alertId, owner, "acknowledged")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("acknowledged"));
        assertThat(stored().getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        // acknowledging a dismissed alert brings it back into the default list
        list(false).andExpect(jsonPath("$.alerts[*].id").value(contains((int) alertId)));
    }

    @Test
    void repeatingTheCurrentStatusChangesNothing() throws Exception {
        Alert alert = stored();
        Instant earlier = Instant.parse("2026-09-21T08:00:00Z");
        alert.setStatus(AlertStatus.ACKNOWLEDGED);
        alert.setStatusChangedAt(earlier);
        alerts.save(alert);

        change(alertId, owner, "acknowledged")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("acknowledged"))
                .andExpect(jsonPath("$.status_changed_at").value("2026-09-21T08:00:00Z"));

        assertThat(stored().getStatusChangedAt()).isEqualTo(earlier);
    }

    @Test
    void anAlertCannotBeSetBackToNew() throws Exception {
        change(alertId, owner, "acknowledged").andExpect(status().isOk());

        change(alertId, owner, "new")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("status must be acknowledged or dismissed"));

        assertThat(stored().getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\": \"archived\"}", "{\"status\": \"Acknowledged\"}", "{\"status\": null}",
            "{}", "{\"status\": 1}", "not json"})
    void aBadBodyIsABadRequestAndChangesNothing(String body) throws Exception {
        mvc.perform(patch("/alerts/" + alertId).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());

        assertThat(stored().getStatus()).isEqualTo(AlertStatus.NEW);
        assertThat(stored().getStatusChangedAt()).isNull();
    }

    @Test
    void anotherUsersAlertLooksTheSameAsAMissingOne() throws Exception {
        long missing = alertId + 1000;

        change(alertId, UUID.randomUUID(), "dismissed")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No alert " + alertId));
        change(missing, owner, "dismissed")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No alert " + missing));

        assertThat(stored().getStatus()).isEqualTo(AlertStatus.NEW);
    }

    @Test
    void anIdThatIsNotANumberIsABadRequest() throws Exception {
        mvc.perform(patch("/alerts/abc").contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"dismissed\"}")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changingAStatusNeedsAUserToken() throws Exception {
        String url = "/alerts/" + alertId;
        String body = "{\"status\": \"dismissed\"}";

        mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.service()))
                .andExpect(status().isForbidden());

        assertThat(stored().getStatus()).isEqualTo(AlertStatus.NEW);
    }

    private ResultActions change(long alert, UUID user, String status) throws Exception {
        return mvc.perform(patch("/alerts/" + alert)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"" + status + "\"}")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(user)));
    }

    private ResultActions list(boolean includeDismissed) throws Exception {
        return mvc.perform(get("/papers/" + paperId + "/alerts")
                .param("include_dismissed", String.valueOf(includeDismissed))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(owner)));
    }

    private Alert stored() {
        return alerts.findById(alertId).orElseThrow();
    }
}
