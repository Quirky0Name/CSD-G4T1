package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A report with everything in it, for Research Evaluation: its alerts, its documents, and the
// evaluation fields impact fills (null until the report is assessed)
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
        String changeSummary,
        AssessmentLevel changeSeverity,
        AssessmentLevel impactLevel,
        JsonNode assessment,
        List<ReportAlertResponse> alerts,
        List<ReportDocumentResponse> documents) {
}
