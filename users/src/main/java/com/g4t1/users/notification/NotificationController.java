package com.g4t1.users.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// A researcher's own notification preferences; the account is the token's, never an id in the path
@RestController
@RequestMapping("/auth/me/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public NotificationPreferencesResponse read(@AuthenticationPrincipal UUID userId) {
        return notifications.find(userId);
    }

    // the whole channel: a threshold left out is cleared, so it never sends on that threshold
    @PutMapping("/email")
    public NotificationPreferencesResponse replaceEmail(@AuthenticationPrincipal UUID userId,
                                                        @RequestBody EmailRequest request) {
        return notifications.replaceEmail(userId, request);
    }
}
