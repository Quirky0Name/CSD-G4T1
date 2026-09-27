package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// a report is opened INVESTIGATING; investigation marks it INVESTIGATED; impact (a later plan) marks it ASSESSED
public enum ReportStatus {
    INVESTIGATING, INVESTIGATED, ASSESSED;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static ReportStatus of(String value) {
        for (ReportStatus status : values()) {
            if (status.value().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown status " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<ReportStatus> {
        public Converter() {
            super(ReportStatus.class);
        }
    }
}
