package com.g4t1.users.notification;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// One test message per account a minute: the address is unverified, so an open endpoint could be looped to spam it.
// Kept in memory, since a restart only resets a 60 second window.
@Component
class TestCooldown {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    static final String TOO_SOON = "Wait " + WINDOW.toSeconds() + " seconds between test messages";

    private final Clock clock;
    private final Map<UUID, Instant> lastTest = new HashMap<>();

    TestCooldown(Clock clock) {
        this.clock = clock;
    }

    // true, and the window starts, when the account has no test in the last minute
    synchronized boolean start(UUID userId) {
        Instant now = clock.instant();
        Instant last = lastTest.get(userId);
        if (last != null && now.isBefore(last.plus(WINDOW))) {
            return false;
        }
        lastTest.put(userId, now);
        return true;
    }
}
