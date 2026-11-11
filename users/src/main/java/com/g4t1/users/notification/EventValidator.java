package com.g4t1.users.notification;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

// The rules of an event's body; a break is a 400 whose detail starts with the field's name
final class EventValidator {

    private static final Set<String> LEVELS = Set.of("low", "medium", "high");
    private static final Set<String> CHANGE_SEVERITIES = Set.of("none", "low", "medium", "high");

    private EventValidator() {
    }

    static void check(NotificationEvent event) {
        if (event.event() == null || !Set.of("alerts", "report_assessed").contains(event.event())) {
            throw badRequest("event must be alerts or report_assessed");
        }
        if (event.paper() == null || event.paper().id() == null) {
            throw badRequest("paper is required");
        }
        if (event.event().equals("alerts")) {
            checkAlerts(event);
        } else {
            checkReport(event);
        }
    }

    private static void checkAlerts(NotificationEvent event) {
        if (event.alerts() == null || event.alerts().isEmpty()) {
            throw badRequest("alerts must have at least one alert");
        }
        for (NotificationEvent.Alert alert : event.alerts()) {
            if (alert == null) {
                throw badRequest("alerts must not contain null");
            }
            if (alert.alertSeverity() == null || !LEVELS.contains(alert.alertSeverity())) {
                throw badRequest("alert_severity must be low, medium or high");
            }
            if (alert.changeType() == null || alert.description() == null) {
                throw badRequest("change_type and description are required");
            }
        }
    }

    private static void checkReport(NotificationEvent event) {
        if (event.changeSeverity() == null || !CHANGE_SEVERITIES.contains(event.changeSeverity())) {
            throw badRequest("change_severity must be none, low, medium or high");
        }
        if (event.changeSummary() == null) {
            throw badRequest("change_summary is required");
        }
        if (event.impactLevel() != null && !LEVELS.contains(event.impactLevel())) {
            throw badRequest("impact_level must be low, medium, high or null");
        }
        if (event.changeSeverity().equals("none")) {
            requireNull("impact_level", event.impactLevel());
            requireNull("evaluation", event.evaluation());
            requireNull("recommendation", event.recommendation());
        }
    }

    private static void requireNull(String field, String value) {
        if (value != null) {
            throw badRequest(field + " must be null when change_severity is none");
        }
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
