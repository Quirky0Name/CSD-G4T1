package com.g4t1.storage.report;

import jakarta.validation.constraints.NotNull;

// The body of PATCH /internal/papers/{id}/reports/{reportId}; only "investigated" is accepted so far
public record ReportStatusChangeRequest(@NotNull ReportStatus status) {
}
