package com.g4t1.storage.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

// The body of POST /internal/papers/{id}/alerts. Checks that need only the request live here;
// checks that need the database live in AlertService. There's no status: a new alert starts as NEW.
public record NewAlertRequest(
        @NotNull ChangeType changeType,
        @NotBlank @Size(max = 512) String changeKey,
        @NotNull Severity severity,
        @NotBlank String description,
        @NotBlank String recommendation,
        @Size(max = 255) String noticeDoi,
        @NotNull Instant detectedAt,
        @NotNull Long snapshotId,
        @NotNull Long previousSnapshotId) {
}
