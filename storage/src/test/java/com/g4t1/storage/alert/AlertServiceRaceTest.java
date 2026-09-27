package com.g4t1.storage.alert;

import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Two requests for the same change race past the lookup; the database's unique constraint
// rejects the second insert. Hard to time through HTTP, so the repository is scripted here.
class AlertServiceRaceTest {

    private final AlertRepository alerts = mock(AlertRepository.class);
    private final PaperRepository papers = mock(PaperRepository.class);
    private final AlertService service = new AlertService(alerts, mock(AlertNoteRepository.class), papers);

    private final UUID paperId = UUID.randomUUID();
    private final NewAlertRequest request = new NewAlertRequest(ChangeType.CORRECTION, "correction:10.1/x",
            Severity.MEDIUM, "A correction was published.", "Read the correction.", "10.1/x",
            Instant.parse("2026-09-20T12:00:00Z"), 42L, 41L);

    @Test
    void theLoserOfARaceGetsTheWinnersAlert() {
        Alert winner = new Alert(paperId, request);
        when(papers.existsById(paperId)).thenReturn(true);
        when(alerts.findByPaperIdAndChangeKey(paperId, "correction:10.1/x"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(alerts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_alerts_paper_change"));

        AlertService.StoredAlert stored = service.store(paperId, request);

        assertThat(stored.created()).isFalse();
        assertThat(stored.alert().description()).isEqualTo("A correction was published.");
    }

    @Test
    void anIntegrityErrorWithNoWinnerIsNotSwallowed() {
        var error = new DataIntegrityViolationException("fk_alerts_paper");
        when(papers.existsById(paperId)).thenReturn(true);
        when(alerts.findByPaperIdAndChangeKey(paperId, "correction:10.1/x")).thenReturn(Optional.empty());
        when(alerts.saveAndFlush(any())).thenThrow(error);

        assertThatThrownBy(() -> service.store(paperId, request)).isSameAs(error);
    }
}
