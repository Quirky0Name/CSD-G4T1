package com.g4t1.users.notification;

// The whole Telegram channel, for PUT /auth/me/notifications/telegram; a threshold or chat id left out is null
public record TelegramRequest(boolean enabled, Long chatId, String minAlertSeverity, String minChangeSeverity,
                              String minImpactLevel) {
}
