package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.g4t1.storage.alert.Alert;
import com.g4t1.storage.alert.AlertStatus;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.alert.Severity;

import java.time.Instant;

// An alert as a report shows it to Research Evaluation. Unlike the frontend's AlertResponse it has
// the change key, which is how Research Evaluation matches the report's alerts to its detected changes.
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReportAlertResponse(
        Long id,
        ChangeType changeType,
        String changeKey,
        Severity severity,
        String description,
        String recommendation,
        String noticeDoi,
        Instant detectedAt,
        AlertStatus status) {

    static ReportAlertResponse from(Alert alert) {
        return new ReportAlertResponse(alert.getId(), alert.getChangeType(), alert.getChangeKey(),
                alert.getSeverity(), alert.getDescription(), alert.getRecommendation(), alert.getNoticeDoi(),
                alert.getDetectedAt(), alert.getStatus());
    }
}
