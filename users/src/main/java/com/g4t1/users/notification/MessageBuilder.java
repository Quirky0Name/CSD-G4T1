package com.g4t1.users.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
                .sorted(Comparator.comparingInt(
                        (NotificationEvent.Alert alert) -> Levels.rank(alert.alertSeverity())).reversed())
                .forEach(alert -> lines.add(alert.alertSeverity().toUpperCase(Locale.ROOT) + " " + alert.changeType()
                        + ": " + alert.description()));
        return new Message("Research Assistant: New alerts on \"" + title(event) + "\"", withLink(event, lines));
    }

    public Message report(NotificationEvent event) {
        List<String> lines = header(event);
        lines.add("");
        lines.add("Change: " + event.changeSeverity());
        lines.add(event.changeSummary());
        if (event.impactLevel() != null || event.evaluation() != null) {
            lines.add("");
        }
        if (event.impactLevel() != null) {
            lines.add("Impact on your draft: " + event.impactLevel());
        }
        if (event.evaluation() != null) {
            lines.add(event.evaluation());
        }
        if (event.recommendation() != null) {
            lines.add("");
            lines.add("What to do:");
            lines.add(event.recommendation());
        }
        return new Message("Research Assistant: Report ready on \"" + title(event) + "\"", withLink(event, lines));
    }

    // fixed text: the researcher asked for it, to see that a channel works
    public Message test() {
        return new Message("Research Assistant: Test notification",
                "This is a test message from Research Assistant. If you can read it, this channel works.");
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
