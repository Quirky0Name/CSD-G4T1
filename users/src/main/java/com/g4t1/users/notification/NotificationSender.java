package com.g4t1.users.notification;

import com.g4t1.users.auth.AccountGoneException;
import com.g4t1.users.auth.User;
import com.g4t1.users.auth.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Applies each channel's thresholds to an event and sends it to the channels it passes, one after the other
@Service
public class NotificationSender {

    // the message a channel gets for its three thresholds, or nothing when they aren't reached
    private interface Matcher {
        Optional<Message> messageFor(String minAlertSeverity, String minChangeSeverity, String minImpactLevel);
    }

    private final NotificationPreferencesRepository preferences;
    private final UserRepository users;
    private final MessageBuilder messages;
    private final EmailChannel email;
    private final TelegramChannel telegram;

    public NotificationSender(NotificationPreferencesRepository preferences, UserRepository users,
                              MessageBuilder messages, EmailChannel email, TelegramChannel telegram) {
        this.preferences = preferences;
        this.users = users;
        this.messages = messages;
        this.email = email;
        this.telegram = telegram;
    }

    public NotificationOutcomes send(UUID userId, NotificationEvent event) {
        EventValidator.check(event);
        User user = users.findById(userId).orElseThrow(() -> noUser(userId));
        NotificationPreferences prefs = preferences.findById(userId).orElseThrow(() -> noUser(userId));
        return deliver(user, prefs, (alert, change, impact) -> match(event, alert, change, impact));
    }

    // the researcher's own test: every enabled channel, whatever its thresholds; a deleted account is a 401
    public NotificationOutcomes sendTest(UUID userId) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        NotificationPreferences prefs = preferences.findById(userId).orElseThrow(() -> noUser(userId));
        return deliver(user, prefs, (alert, change, impact) -> Optional.of(messages.test()));
    }

    private NotificationOutcomes deliver(User user, NotificationPreferences prefs, Matcher matcher) {
        Map<String, Outcome> channels = new LinkedHashMap<>();
        channels.put("email", sendEmail(user, prefs, matcher));
        channels.put("telegram", sendTelegram(prefs, matcher));
        return new NotificationOutcomes(channels);
    }

    private Outcome sendEmail(User user, NotificationPreferences prefs, Matcher matcher) {
        if (!prefs.isEmailEnabled()) {
            return Outcome.DISABLED;
        }
        return matcher.messageFor(prefs.getEmailMinAlertSeverity(), prefs.getEmailMinChangeSeverity(),
                        prefs.getEmailMinImpactLevel())
                .map(message -> email.send(user.getId(), user.getEmail(), message))
                .orElse(Outcome.NO_MATCH);
    }

    private Outcome sendTelegram(NotificationPreferences prefs, Matcher matcher) {
        if (!prefs.isTelegramEnabled()) {
            return Outcome.DISABLED;
        }
        return matcher.messageFor(prefs.getTelegramMinAlertSeverity(), prefs.getTelegramMinChangeSeverity(),
                        prefs.getTelegramMinImpactLevel())
                .map(message -> telegram.send(prefs.getUserId(), prefs.getTelegramChatId(), message.text()))
                .orElse(Outcome.NO_MATCH);
    }

    private Optional<Message> match(NotificationEvent event, String minAlertSeverity, String minChangeSeverity,
                                    String minImpactLevel) {
        if (event.event().equals("alerts")) {
            List<NotificationEvent.Alert> passing = event.alerts().stream()
                    .filter(alert -> Levels.reaches(alert.alertSeverity(), minAlertSeverity))
                    .toList();
            return passing.isEmpty() ? Optional.empty() : Optional.of(messages.alerts(event, passing));
        }
        boolean passes = Levels.reaches(event.changeSeverity(), minChangeSeverity)
                || Levels.reaches(event.impactLevel(), minImpactLevel);
        return passes ? Optional.of(messages.report(event)) : Optional.empty();
    }

    private static ResponseStatusException noUser(UUID userId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No user " + userId);
    }
}
