package com.g4t1.storage.report;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Impact's reports, by report id alone: investigation hands impact bare report ids, and the report
// names its paper. Service-token only (SecurityConfig limits /internal/** to ROLE_SERVICE)
@RestController
@RequestMapping("/internal/reports/{reportId}")
public class InternalReportByIdController {

    private final ReportService reports;

    public InternalReportByIdController(ReportService reports) {
        this.reports = reports;
    }

    // the same body as GET /internal/papers/{paperId}/reports/{reportId}
    @GetMapping
    public ReportResponse get(@PathVariable long reportId) {
        return reports.get(reportId);
    }

    // stores impact's evaluation once and marks the report assessed
    @PutMapping("/evaluation")
    public ReportResponse recordEvaluation(@PathVariable long reportId,
                                           @Valid @RequestBody ReportEvaluationRequest request) {
        return reports.recordEvaluation(reportId, request);
    }
}
