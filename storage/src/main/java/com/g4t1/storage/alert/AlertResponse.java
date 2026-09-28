package com.g4t1.storage.alert;

import java.time.Instant;
import java.util.UUID;

// response -> user view -> remove technicals
public record AlertResponse(
        Long id,
        UUID paperId,
        ChangeType changeType,
        Severity severity,
        String description,
        String recommendation,
        String noticeDoi,
        Instant detectedAt,
        AlertStatus status,
        Instant statusChangedAt) {

    // public for the frontend's reports, which show their alerts in this shape
    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getPaperId(),
                alert.getChangeType(),
                alert.getSeverity(),
                alert.getDescription(),
                alert.getRecommendation(),
                alert.getNoticeDoi(),
                alert.getDetectedAt(),
                alert.getStatus(),
                alert.getStatusChangedAt());
    }
}
