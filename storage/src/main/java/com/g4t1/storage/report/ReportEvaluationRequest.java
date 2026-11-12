package com.g4t1.storage.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

// The body of PUT /internal/reports/{reportId}/evaluation: impact's result. With change_severity none
// (not meaningful) only the summary is sent; otherwise impact_level, evaluation and recommendation are
// required too. ReportService checks that rule; assessment is stored as-is.
public record ReportEvaluationRequest(
        @NotBlank String changeSummary,
        @NotNull AssessmentLevel changeSeverity,
        AssessmentLevel impactLevel,
        String evaluation,
        String recommendation,
        JsonNode assessment,
        JsonNode claims,
        // the past chunks impact added to the prompt, stored as sent and never looked inside (null: none sent)
        JsonNode contextChunks) {

    // the same evaluation, with other context items
    public ReportEvaluationRequest withContextChunks(JsonNode items) {
        return new ReportEvaluationRequest(changeSummary, changeSeverity, impactLevel, evaluation, recommendation,
                assessment, claims, items);
    }
}
