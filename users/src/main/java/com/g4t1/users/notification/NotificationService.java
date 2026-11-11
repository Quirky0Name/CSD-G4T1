package com.g4t1.users.notification;

import com.g4t1.users.auth.AccountGoneException;
import com.g4t1.users.auth.User;
import com.g4t1.users.auth.UserRepository;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Set<String> LEVELS = Set.of("low", "medium", "high");

    private final NotificationPreferencesRepository preferences;
    private final UserRepository users;

    public NotificationService(NotificationPreferencesRepository preferences, UserRepository users) {
        this.preferences = preferences;
        this.users = users;
    }

    // the account is read too, for the address; a deleted account is a 401, which signs the frontend out
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse find(UUID userId) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        return NotificationPreferencesResponse.from(preferences.findById(userId).orElseThrow(), user.getEmail());
    }

    @Transactional
    public NotificationPreferencesResponse replaceEmail(UUID userId, EmailRequest request) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        boolean enabled = enabled(request.enabled());
        String alert = level("min_alert_severity", request.minAlertSeverity());
        String change = level("min_change_severity", request.minChangeSeverity());
        String impact = level("min_impact_level", request.minImpactLevel());
        NotificationPreferences prefs = preferences.findForUpdate(userId).orElseThrow();
        prefs.replaceEmail(enabled, alert, change, impact);
        return NotificationPreferencesResponse.from(preferences.save(prefs), user.getEmail());
    }

    @Transactional
    public NotificationPreferencesResponse replaceTelegram(UUID userId, TelegramRequest request) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        boolean enabled = enabled(request.enabled());
        Long chatId = chatId(request.chatId());
        String alert = level("min_alert_severity", request.minAlertSeverity());
        String change = level("min_change_severity", request.minChangeSeverity());
        String impact = level("min_impact_level", request.minImpactLevel());
        if (enabled && chatId == null) {
            throw badRequest("chat_id is required to enable Telegram");
        }
        NotificationPreferences prefs = preferences.findForUpdate(userId).orElseThrow();
        prefs.replaceTelegram(enabled, chatId, alert, change, impact);
        return NotificationPreferencesResponse.from(preferences.save(prefs), user.getEmail());
    }

    private static boolean enabled(Boolean value) {
        if (value == null) {
            throw badRequest("enabled is required");
        }
        return value;
    }

    private static String level(String field, String value) {
        if (value != null && !LEVELS.contains(value)) {
            throw badRequest(field + " must be low, medium, high or null");
        }
        return value;
    }

    // a JSON integer other than 0 (a group's id is negative), or null
    private static Long chatId(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() == 0) {
            throw badRequest("chat_id must be a non-zero integer or null");
        }
        return value.longValue();
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
