package com.g4t1.users.notification;

import java.util.Map;

// The answer to a send: each channel's outcome, keyed email and telegram
public record NotificationOutcomes(Map<String, Outcome> channels) {
}
