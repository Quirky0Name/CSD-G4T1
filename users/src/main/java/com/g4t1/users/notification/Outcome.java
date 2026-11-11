package com.g4t1.users.notification;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// What happened to one channel of one notification
public enum Outcome {
    SENT, FAILED, DISABLED, NO_MATCH, NOT_CONFIGURED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
