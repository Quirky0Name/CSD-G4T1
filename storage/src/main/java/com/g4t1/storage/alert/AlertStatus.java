package com.g4t1.storage.alert;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// every alert starts NEW; the researcher moves it to ACKNOWLEDGED or DISMISSED
public enum AlertStatus {
    NEW, ACKNOWLEDGED, DISMISSED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AlertStatus of(String value) {
        for (AlertStatus status : values()) {
            if (status.value().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<AlertStatus> {
        public Converter() {
            super(AlertStatus.class);
        }
    }
}
