package com.g4t1.users.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Writes a notification's subject and plain text; the link to the paper is left out when APP_URL isn't set
@Component
public class MessageBuilder {

    private static final String NO_TITLE = "a tracked paper";

    private final String appUrl;

    public MessageBuilder(@Value("${users.app-url:}") String appUrl) {
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    // the alerts passed in, most severe first
    public Message alerts(NotificationEvent event, List<NotificationEvent.Alert> passing) {
        List<String> lines = header(event);
        lines.add("");
        passing.stream()
                .sorted(Comparator.comparingInt((NotificationEvent.Alert alert) -> Levels.rank(alert.alertSeverity())).reversed())
                .forEach(alert -> lines.add(alert.alertSeverity().toUpperCase() + " " + alert.changeType() + ": "
                        + alert.description()));
        return new Message("Research Assistant: New alerts on \"" + title(event) + "\"", withLink(event, lines));
    }

    private List<String> header(NotificationEvent event) {
        List<String> lines = new ArrayList<>();
        lines.add(title(event));
        if (event.paper().doi() != null) {
            lines.add("DOI: " + event.paper().doi());
        }
        return lines;
    }

    private String withLink(NotificationEvent event, List<String> lines) {
        if (!appUrl.isEmpty()) {
            lines.add("");
            lines.add(appUrl + "/papers/" + event.paper().id());
        }
        return String.join("\n", lines);
    }

    private static String title(NotificationEvent event) {
        return event.paper().title() != null ? event.paper().title() : NO_TITLE;
    }
}
