package com.g4t1.users.notification;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * An event from Research Evaluation for one researcher. Service tokens only, and reachable only on
 * the compose network: neither Caddy nor the Vite proxy forwards /internal.
 */
@RestController
public class InternalNotificationController {

    private final NotificationSender sender;

    public InternalNotificationController(NotificationSender sender) {
        this.sender = sender;
    }

    @PostMapping("/internal/users/{userId}/notifications")
    public NotificationOutcomes send(@PathVariable UUID userId, @RequestBody NotificationEvent event) {
        return sender.send(userId, event);
    }
}
