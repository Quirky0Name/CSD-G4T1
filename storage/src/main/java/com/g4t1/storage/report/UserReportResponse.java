package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g4t1.storage.alert.AlertResponse;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// A report as the researcher sees it: its alerts in the alert API's shape (no change_key), its
// documents, the evaluation fields impact will fill (null until then), and the past chunks impact
// added to its prompt (a JSON array as Research Evaluation sent it; null when none was sent). Never
// impact's raw assessment: claims carry what the researcher needs from it
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
        String changeSummary,
        AssessmentLevel changeSeverity,
        AssessmentLevel impactLevel,
        JsonNode claims,
        List<AlertResponse> alerts,
        List<UserDocumentResponse> documents,
        JsonNode contextChunks) {

    static UserReportResponse from(Report report, JsonNode claims, List<AlertResponse> alerts,
                                   List<UserDocumentResponse> documents, JsonNode contextChunks) {
        return new UserReportResponse(report.getId(), report.getPaperId(), report.getStatus(), report.getCreatedAt(),
                report.getInvestigatedAt(), report.getEvaluation(), report.getRecommendation(),
                report.getEvaluatedAt(), report.getChangeSummary(), report.getChangeSeverity(),
                report.getImpactLevel(), claims, alerts, documents, contextChunks);
    }
}
