package com.g4t1.users.notification;

import com.fasterxml.jackson.annotation.JsonInclude;

// A researcher's preferences for both channels; a threshold or the chat id is null when unset
public record NotificationPreferencesResponse(Email email, Telegram telegram) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Email(boolean enabled, String address, String minAlertSeverity, String minChangeSeverity,
                        String minImpactLevel) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Telegram(boolean enabled, Long chatId, String minAlertSeverity, String minChangeSeverity,
                           String minImpactLevel) {
    }

    // the address is the account's email, which this page can't change
    static NotificationPreferencesResponse from(NotificationPreferences prefs, String address) {
        return new NotificationPreferencesResponse(
                new Email(prefs.isEmailEnabled(), address, prefs.getEmailMinAlertSeverity(),
                        prefs.getEmailMinChangeSeverity(), prefs.getEmailMinImpactLevel()),
                new Telegram(prefs.isTelegramEnabled(), prefs.getTelegramChatId(), prefs.getTelegramMinAlertSeverity(),
                        prefs.getTelegramMinChangeSeverity(), prefs.getTelegramMinImpactLevel()));
    }
}
