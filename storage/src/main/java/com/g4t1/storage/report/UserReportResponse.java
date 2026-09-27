package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g4t1.storage.alert.AlertResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A report as the researcher sees it: its alerts in the alert API's shape (no change_key), its
// documents, and the evaluation fields impact will fill (null until then)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserReportResponse(
        Long id,
        UUID paperId,
        ReportStatus status,
        Instant createdAt,
        Instant investigatedAt,
        String evaluation,
        String recommendation,
        Instant evaluatedAt,
        List<AlertResponse> alerts,
        List<UserDocumentResponse> documents) {

    static UserReportResponse from(Report report, List<AlertResponse> alerts, List<UserDocumentResponse> documents) {
        return new UserReportResponse(report.getId(), report.getPaperId(), report.getStatus(), report.getCreatedAt(),
                report.getInvestigatedAt(), report.getEvaluation(), report.getRecommendation(),
                report.getEvaluatedAt(), alerts, documents);
    }
}
