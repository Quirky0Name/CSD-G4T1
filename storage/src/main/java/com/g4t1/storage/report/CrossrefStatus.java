package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// how Research Evaluation's Crossref lookup of the document's DOI went
public enum CrossrefStatus {
    OK, NOT_FOUND, ERROR;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static CrossrefStatus of(String value) {
        for (CrossrefStatus status : values()) {
            if (status.value().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown crossref_status " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<CrossrefStatus> {
        public Converter() {
            super(CrossrefStatus.class);
        }
    }
}
