package com.g4t1.storage.report;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// The frontend's view of a paper's reports; user token only (SecurityConfig gives everything outside
// /internal/** to ROLE_USER)
@RestController
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    // every report of the paper, newest first, each with its alerts and documents
    @GetMapping("/papers/{paperId}/reports")
    public ReportListResponse list(@AuthenticationPrincipal UUID userId, @PathVariable UUID paperId) {
        return new ReportListResponse(reports.listForOwner(userId, paperId));
    }

    // the owner's verdict and note on an assessed report, replacing any earlier feedback
    @PutMapping("/papers/{paperId}/reports/{reportId}/feedback")
    public ReportFeedbackResponse saveFeedback(@AuthenticationPrincipal UUID userId, @PathVariable UUID paperId,
                                               @PathVariable long reportId,
                                               @Valid @RequestBody ReportFeedbackRequest request) {
        return reports.saveFeedback(userId, paperId, reportId, request);
    }

    // clears the report's feedback; 204 also when it has none
    @DeleteMapping("/papers/{paperId}/reports/{reportId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFeedback(@AuthenticationPrincipal UUID userId, @PathVariable UUID paperId,
                               @PathVariable long reportId) {
        reports.deleteFeedback(userId, paperId, reportId);
    }
}
