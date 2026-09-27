package com.g4t1.storage.report;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
