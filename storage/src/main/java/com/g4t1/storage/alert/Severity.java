package com.g4t1.storage.alert;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// declared most severe first: AlertService lists alerts from the same poll in this order
public enum Severity {
    HIGH, MEDIUM, LOW;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static Severity of(String value) {
        for (Severity severity : values()) {
            if (severity.value().equals(value)) {
                return severity;
            }
        }
        throw new IllegalArgumentException("Unknown severity " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<Severity> {
        public Converter() {
            super(Severity.class);
        }
    }
}
