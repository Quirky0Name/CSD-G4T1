package com.g4t1.storage.report;

import jakarta.validation.constraints.NotNull;

// The body of PUT /papers/{paperId}/reports/{reportId}/feedback. The note is optional: ReportService
// trims it, stores blank as null and rejects one longer than its MAX_FEEDBACK_NOTE
public record ReportFeedbackRequest(@NotNull FeedbackVerdict verdict, String note) {
}
