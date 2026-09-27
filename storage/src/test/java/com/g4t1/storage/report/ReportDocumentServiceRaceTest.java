package com.g4t1.storage.report;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Two requests for the same DOI of a report race past the lookup; the database's unique constraint
// rejects the second insert. Hard to time through HTTP, so the repositories are scripted here.
class ReportDocumentServiceRaceTest {

    private final ReportDocumentRepository documents = mock(ReportDocumentRepository.class);
    private final ReportRepository reports = mock(ReportRepository.class);
    private final ReportDocumentService service =
            new ReportDocumentService(documents, reports, JsonMapper.builder().build());

    private final NewReportDocumentRequest request =
            InternalReportTest.documentRequest(7L, "notice", "10.1016/j.ijantimicag.2024.107416");

    @Test
    void theLoserOfARaceGetsTheWinnersRow() {
        ReportDocument winner = new ReportDocument(request, null);
        when(reports.existsById(7L)).thenReturn(true);
        when(documents.findByReportIdAndDoi(7L, "10.1016/j.ijantimicag.2024.107416"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(documents.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_report_documents"));

        ReportDocumentService.StoredDocument stored = service.store(request);

        assertThat(stored.created()).isFalse();
        assertThat(stored.document().doi()).isEqualTo("10.1016/j.ijantimicag.2024.107416");
    }

    @Test
    void anIntegrityErrorWithNoWinnerIsNotSwallowed() {
        var error = new DataIntegrityViolationException("fk_report_documents_report");
        when(reports.existsById(7L)).thenReturn(true);
        when(documents.findByReportIdAndDoi(7L, "10.1016/j.ijantimicag.2024.107416")).thenReturn(Optional.empty());
        when(documents.saveAndFlush(any())).thenThrow(error);

        assertThatThrownBy(() -> service.store(request)).isSameAs(error);
    }
}
