package com.g4t1.storage.alert;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// declared most severe first: AlertService lists alerts from the same poll in this order
public enum AlertSeverity {
    HIGH, MEDIUM, LOW;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AlertSeverity of(String value) {
        for (AlertSeverity severity : values()) {
            if (severity.value().equals(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown alert severity " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<AlertSeverity> {
        public Converter() {
            super(AlertSeverity.class);
        }
    }
}
