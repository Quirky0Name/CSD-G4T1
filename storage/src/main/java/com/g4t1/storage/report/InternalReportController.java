package com.g4t1.storage.report;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Research Evaluation's reports; service-token only (SecurityConfig limits /internal/** to ROLE_SERVICE)
@RestController
@RequestMapping("/internal/papers/{paperId}/reports")
public class InternalReportController {

    private final ReportService reports;

    public InternalReportController(ReportService reports) {
        this.reports = reports;
    }

    // 201 with the new report, or 204 when the paper has no alerts outside a report
    @PostMapping
    public ResponseEntity<ReportResponse> open(@PathVariable UUID paperId) {
        return reports.open(paperId)
                .map(report -> ResponseEntity.status(HttpStatus.CREATED).body(report))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{reportId}")
    public ReportResponse get(@PathVariable UUID paperId, @PathVariable long reportId) {
        return reports.get(paperId, reportId);
    }

    @PatchMapping("/{reportId}")
    public ReportResponse changeStatus(@PathVariable UUID paperId, @PathVariable long reportId,
                                       @Valid @RequestBody ReportStatusChangeRequest request) {
        return reports.changeStatus(paperId, reportId, request.status());
    }
}
