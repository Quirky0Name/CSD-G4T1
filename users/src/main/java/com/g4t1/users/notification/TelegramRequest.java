package com.g4t1.users.notification;

import tools.jackson.databind.JsonNode;

/**
 * The whole Telegram channel, for PUT /auth/me/notifications/telegram; a threshold or chat id left
 * out is null. The chat id stays a JsonNode so NotificationService can refuse "123", which Jackson
 * would otherwise turn into a number. Nothing else is checked here either.
 */
public record TelegramRequest(Boolean enabled, JsonNode chatId, String minAlertSeverity, String minChangeSeverity,
                              String minImpactLevel) {
}
