package com.g4t1.storage.alert;

import com.g4t1.storage.TestTokens;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalAlertTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PaperRepository papers;

    @Autowired
    AlertRepository alerts;

    @Autowired
    JdbcTemplate jdbc;

    private UUID paperId;

    @BeforeEach
    void clean() {
        alerts.deleteAll();
        papers.deleteAll();
        paperId = papers.save(new Paper(UUID.randomUUID())).getId();
    }

    @Test
    void newAlertIsStoredAndReturnedWithoutItsInternalColumns() throws Exception {
        store(paperId, body())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.paper_id").value(paperId.toString()))
                .andExpect(jsonPath("$.change_type").value("retraction"))
                .andExpect(jsonPath("$.severity").value("high"))
                .andExpect(jsonPath("$.description").value("This paper was retracted."))
                .andExpect(jsonPath("$.recommendation").value("Stop relying on its findings."))
                .andExpect(jsonPath("$.notice_doi").value("10.1016/j.ijantimicag.2020.106063"))
                .andExpect(jsonPath("$.detected_at").value("2026-09-20T12:00:00Z"))
                .andExpect(jsonPath("$.status").value("new"))
                .andExpect(jsonPath("$.status_changed_at").value(nullValue()))
                .andExpect(jsonPath("$.change_key").doesNotExist())
                .andExpect(jsonPath("$.snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.previous_snapshot_id").doesNotExist())
                .andExpect(jsonPath("$.created_at").doesNotExist());

        // read back from the DB, not the object the response was built from
        Alert saved = alerts.findAll().getFirst();
        assertThat(saved.getPaperId()).isEqualTo(paperId);
        assertThat(saved.getChangeType()).isEqualTo(ChangeType.RETRACTION);
        assertThat(saved.getChangeKey()).isEqualTo("retraction");
        assertThat(saved.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(saved.getDescription()).isEqualTo("This paper was retracted.");
        assertThat(saved.getRecommendation()).isEqualTo("Stop relying on its findings.");
        assertThat(saved.getNoticeDoi()).isEqualTo("10.1016/j.ijantimicag.2020.106063");
        assertThat(saved.getDetectedAt()).isEqualTo(Instant.parse("2026-09-20T12:00:00Z"));
        assertThat(saved.getSnapshotId()).isEqualTo(42L);
        assertThat(saved.getPreviousSnapshotId()).isEqualTo(41L);
        assertThat(saved.getStatus()).isEqualTo(AlertStatus.NEW);
        assertThat(saved.getStatusChangedAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        // the raw columns hold the same lowercase values as the API, not the enum names
        List<String> columns = jdbc.queryForObject("select change_type, severity, status from alerts",
                (row, n) -> List.of(row.getString(1), row.getString(2), row.getString(3)));
        assertThat(columns).containsExactly("retraction", "high", "new");
    }

    @Test
    void noticeDoiIsOptional() throws Exception {
        Map<String, Object> body = body();
        body.remove("notice_doi");

        store(paperId, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notice_doi").value(nullValue()));
    }

    @ParameterizedTest
    @EnumSource(ChangeType.class)
    void everyChangeTypeIsAccepted(ChangeType type) throws Exception {
        Map<String, Object> body = body();
        body.put("change_type", type.value());
        body.put("change_key", type.value() + ":10.1/x");

        store(paperId, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.change_type").value(type.value()));
    }

    @Test
    void theSameChangeKeyReturnsTheStoredAlertUnchanged() throws Exception {
        store(paperId, body()).andExpect(status().isCreated());
        Alert first = alerts.findAll().getFirst();
        Instant acknowledgedAt = Instant.parse("2026-09-21T08:00:00Z");
        first.setStatus(AlertStatus.ACKNOWLEDGED);
        first.setStatusChangedAt(acknowledgedAt);
        alerts.save(first);

        Map<String, Object> resent = body();
        resent.put("description", "A different description");
        resent.put("severity", "low");
        store(paperId, resent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first.getId()))
                .andExpect(jsonPath("$.description").value("This paper was retracted."))
                .andExpect(jsonPath("$.severity").value("high"))
                .andExpect(jsonPath("$.status").value("acknowledged"))
                .andExpect(jsonPath("$.status_changed_at").value("2026-09-21T08:00:00Z"));

        assertThat(alerts.count()).isEqualTo(1);
        Alert kept = alerts.findAll().getFirst();
        assertThat(kept.getDescription()).isEqualTo("This paper was retracted.");
        assertThat(kept.getStatus()).isEqualTo(AlertStatus.ACKNOWLEDGED);
    }

    @Test
    void theSameChangeKeyOnAnotherPaperIsASeparateAlert() throws Exception {
        UUID otherPaper = papers.save(new Paper(UUID.randomUUID())).getId();

        store(paperId, body()).andExpect(status().isCreated());
        store(otherPaper, body()).andExpect(status().isCreated());

        assertThat(alerts.count()).isEqualTo(2);
    }

    @Test
    void aStatusInTheBodyIsIgnoredAndTheAlertStartsAsNew() throws Exception {
        Map<String, Object> body = body();
        body.put("status", "acknowledged");

        store(paperId, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("new"));
        assertThat(alerts.findAll().getFirst().getStatus()).isEqualTo(AlertStatus.NEW);
    }

    @Test
    void unknownPaperIsNotFound() throws Exception {
        UUID missing = UUID.randomUUID();

        store(missing, body())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No paper " + missing));
        assertThat(alerts.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"change_type", "change_key", "severity", "description", "recommendation",
            "detected_at", "snapshot_id", "previous_snapshot_id"})
    void aMissingRequiredFieldIsABadRequest(String field) throws Exception {
        Map<String, Object> body = body();
        body.remove(field);

        store(paperId, body).andExpect(status().isBadRequest());
        assertThat(alerts.count()).isZero();
    }

    @Test
    void blankTextIsABadRequest() throws Exception {
        for (String field : new String[]{"change_key", "description", "recommendation"}) {
            Map<String, Object> body = body();
            body.put(field, "  ");
            store(paperId, body).andExpect(status().isBadRequest());
        }
        assertThat(alerts.count()).isZero();
    }

    @Test
    void unknownEnumValuesAreABadRequest() throws Exception {
        Map<String, Object> badType = body();
        badType.put("change_type", "withdrawal");
        store(paperId, badType).andExpect(status().isBadRequest());

        Map<String, Object> badSeverity = body();
        badSeverity.put("severity", "urgent");
        store(paperId, badSeverity).andExpect(status().isBadRequest());

        // only the exact lowercase value is accepted
        Map<String, Object> upperCase = body();
        upperCase.put("severity", "HIGH");
        store(paperId, upperCase).andExpect(status().isBadRequest());

        assertThat(alerts.count()).isZero();
    }

    @Test
    void aDetectionTimeThatIsNotATimestampIsABadRequest() throws Exception {
        Map<String, Object> body = body();
        body.put("detected_at", "yesterday");

        store(paperId, body).andExpect(status().isBadRequest());
        assertThat(alerts.count()).isZero();
    }

    @Test
    void anOverlongChangeKeyIsABadRequest() throws Exception {
        Map<String, Object> body = body();
        body.put("change_key", "x".repeat(513));

        store(paperId, body).andExpect(status().isBadRequest());
        assertThat(alerts.count()).isZero();
    }

    @Test
    void storingNeedsAServiceToken() throws Exception {
        String payload = json.writeValueAsString(body());
        String url = "/internal/papers/" + paperId + "/alerts";

        mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON).content(payload)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID())))
                .andExpect(status().isForbidden());

        assertThat(alerts.count()).isZero();
    }

    @Test
    void theDatabaseItselfRejectsADuplicateChangeKey() throws Exception {
        store(paperId, body()).andExpect(status().isCreated());
        NewAlertRequest duplicate = json.readValue(json.writeValueAsString(body()), NewAlertRequest.class);

        // the constraint that backs up AlertService when two requests race past its lookup
        assertThatThrownBy(() -> alerts.saveAndFlush(new Alert(paperId, duplicate)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResultActions store(UUID paper, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/internal/papers/" + paper + "/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .header(HttpHeaders.AUTHORIZATION, TestTokens.service()));
    }

    private static Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("change_type", "retraction");
        body.put("change_key", "retraction");
        body.put("severity", "high");
        body.put("description", "This paper was retracted.");
        body.put("recommendation", "Stop relying on its findings.");
        body.put("notice_doi", "10.1016/j.ijantimicag.2020.106063");
        body.put("detected_at", "2026-09-20T12:00:00Z");
        body.put("snapshot_id", 42);
        body.put("previous_snapshot_id", 41);
        return body;
    }
}
