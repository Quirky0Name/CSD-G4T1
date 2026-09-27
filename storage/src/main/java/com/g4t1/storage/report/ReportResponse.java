package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A report with everything in it, for Research Evaluation: its alerts, its documents, and the
// evaluation fields impact will fill (null until then)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReportResponse(
        Long id,
        UUID paperId,
        ReportStatus status,
        Instant createdAt,
        Instant investigatedAt,
        String evaluation,
        String recommendation,
        Instant evaluatedAt,
        List<ReportAlertResponse> alerts,
        List<ReportDocumentResponse> documents) {
}
