package com.g4t1.storage.report;

import com.g4t1.storage.alert.AlertRepository;
import com.g4t1.storage.paper.PaperRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Two opens for one paper race past the "any unreported alerts?" check; the conditional update then
// gives the alerts to only one of them. Hard to time through HTTP, so the repositories are scripted.
class ReportServiceRaceTest {

    private final ReportRepository reports = mock(ReportRepository.class);
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final PaperRepository papers = mock(PaperRepository.class);
    private final ReportDocumentService documents = mock(ReportDocumentService.class);
    private final ReportService service = new ReportService(reports, alerts, papers, documents,
            JsonMapper.builder().build());

    private final UUID paperId = UUID.randomUUID();
    private final Report report = mock(Report.class);

    @Test
    void theLoserOfARaceGetsNoReportAndKeepsNone() {
        when(papers.existsById(paperId)).thenReturn(true);
        when(alerts.existsByPaperIdAndReportIdIsNull(paperId)).thenReturn(true);
        when(report.getId()).thenReturn(7L);
        when(reports.saveAndFlush(any())).thenReturn(report);
        // the winner already took every alert, so the update finds none
        when(alerts.assignUnreportedToReport(paperId, 7L)).thenReturn(0);

        assertThat(service.open(paperId)).isEmpty();
        verify(reports).delete(report);
    }

    @Test
    void theWinnerKeepsItsReport() {
        when(papers.existsById(paperId)).thenReturn(true);
        when(alerts.existsByPaperIdAndReportIdIsNull(paperId)).thenReturn(true);
        when(report.getId()).thenReturn(7L);
        when(report.getPaperId()).thenReturn(paperId);
        when(reports.saveAndFlush(any())).thenReturn(report);
        when(alerts.assignUnreportedToReport(paperId, 7L)).thenReturn(2);
        when(alerts.findByReportIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(documents.forReport(anyLong())).thenReturn(List.of());

        assertThat(service.open(paperId)).isPresent();
        verify(reports, never()).delete(any());
    }

    // Two evaluations of one report race past nothing but the conditional update itself: the one that
    // updates nothing reads the report back and answers 409, writing nothing
    @Test
    void aLostEvaluationRaceIsAConflict() {
        when(reports.recordEvaluation(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(report.getStatus()).thenReturn(ReportStatus.ASSESSED);
        when(reports.findById(7L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.recordEvaluation(7L, evaluation()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("Report 7 is already assessed");
                });
        verify(reports, never()).save(any());
    }

    @Test
    void anEvaluationOfAReportStillInvestigatingIsAConflict() {
        when(reports.recordEvaluation(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(report.getStatus()).thenReturn(ReportStatus.INVESTIGATING);
        when(reports.findById(7L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> service.recordEvaluation(7L, evaluation()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getReason()).isEqualTo("Report 7 is not investigated yet"));
    }

    private static ReportEvaluationRequest evaluation() {
        return new ReportEvaluationRequest("Retracted.", AssessmentLevel.HIGH, AssessmentLevel.HIGH,
                "It affects your draft.", "Remove the citation.", null);
    }
}
