package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// impact's two levels on a report: the change's severity (NONE = not meaningful) and the impact on the draft
public enum AssessmentLevel {
    NONE, LOW, MEDIUM, HIGH;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static AssessmentLevel of(String value) {
        for (AssessmentLevel level : values()) {
            if (level.value().equals(value)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown level " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<AssessmentLevel> {
        public Converter() {
            super(AssessmentLevel.class);
        }
    }
}
