package com.g4t1.users.notification;

import com.g4t1.users.auth.User;
import com.g4t1.users.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Applies each channel's thresholds to an event and sends it to the channels it passes, one after the other
@Service
public class NotificationSender {

    private final NotificationPreferencesRepository preferences;
    private final UserRepository users;
    private final MessageBuilder messages;
    private final EmailChannel email;

    public NotificationSender(NotificationPreferencesRepository preferences, UserRepository users,
                              MessageBuilder messages, EmailChannel email) {
        this.preferences = preferences;
        this.users = users;
        this.messages = messages;
        this.email = email;
    }

    public NotificationOutcomes send(UUID userId, NotificationEvent event) {
        User user = users.findById(userId).orElseThrow(() -> noUser(userId));
        NotificationPreferences prefs = preferences.findById(userId).orElseThrow(() -> noUser(userId));
        Map<String, Outcome> channels = new LinkedHashMap<>();
        channels.put("email", sendEmail(user, prefs, event));
        channels.put("telegram", Outcome.DISABLED);
        return new NotificationOutcomes(channels);
    }

    private Outcome sendEmail(User user, NotificationPreferences prefs, NotificationEvent event) {
        if (!prefs.isEmailEnabled()) {
            return Outcome.DISABLED;
        }
        List<NotificationEvent.Alert> passing = event.alerts().stream()
                .filter(alert -> Levels.reaches(alert.alertSeverity(), prefs.getEmailMinAlertSeverity()))
                .toList();
        if (passing.isEmpty()) {
            return Outcome.NO_MATCH;
        }
        return email.send(user.getId(), user.getEmail(), messages.alerts(event, passing));
    }

    private static ResponseStatusException noUser(UUID userId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No user " + userId);
    }
}
