package com.g4t1.users.notification;

import java.util.List;
import java.util.UUID;

/**
 * What Research Evaluation posts: an alerts event carries alerts, a report_assessed event carries
 * the stored evaluation. One record for both, since the event field says which fields to read.
 */
public record NotificationEvent(String event, Paper paper, Long reportId, List<Alert> alerts,
                                String changeSeverity, String changeSummary, String impactLevel,
                                String evaluation, String recommendation) {

    static final String ALERTS = "alerts";

    public record Paper(UUID id, String title, String doi) {
    }

    public record Alert(Long id, String changeType, String alertSeverity, String description) {
    }
}
