package com.g4t1.storage.report;

// published inside the transaction that saves or deletes a report's feedback; its listener acts after commit
public record FeedbackChanged(long reportId) {
}
