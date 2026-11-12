package com.g4t1.users.notification;

/**
 * The whole email channel, for PUT /auth/me/notifications/email; a threshold left out is null.
 * Nothing is checked here: NotificationService checks it, so a missing enabled is a 400 naming it.
 */
public record EmailRequest(Boolean enabled, String minAlertSeverity, String minChangeSeverity,
                           String minImpactLevel) {
}
