package com.g4t1.users.notification;

// The whole email channel, for PUT /auth/me/notifications/email; a threshold left out is null
public record EmailRequest(boolean enabled, String minAlertSeverity, String minChangeSeverity,
                           String minImpactLevel) {
}
