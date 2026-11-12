package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

// A report's feedback as stored, on both report reads and as PUT .../feedback's answer
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReportFeedbackResponse(FeedbackVerdict verdict, String note, Instant updatedAt) {

    // null when the report has none
    static ReportFeedbackResponse of(Report report) {
        return report.getFeedbackVerdict() == null ? null
                : new ReportFeedbackResponse(report.getFeedbackVerdict(), report.getFeedbackNote(),
                report.getFeedbackUpdatedAt());
    }
}
