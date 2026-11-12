package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// the researcher's verdict on an assessed report: how serious the report as a whole made the change out to be
public enum FeedbackVerdict {
    FAIR, OVERSTATED, UNDERSTATED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static FeedbackVerdict of(String value) {
        for (FeedbackVerdict verdict : values()) {
            if (verdict.value().equals(value)) {
                return verdict;
            }
        }
        throw new IllegalArgumentException("Unknown verdict " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<FeedbackVerdict> {
        public Converter() {
            super(FeedbackVerdict.class);
        }
    }
}
