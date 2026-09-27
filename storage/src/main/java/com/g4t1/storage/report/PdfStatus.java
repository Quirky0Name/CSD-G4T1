package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// SKIPPED for notices (they get no PDF); PENDING until Storage Management tries the download,
// then OK or NOT_FOUND
public enum PdfStatus {
    SKIPPED, PENDING, OK, NOT_FOUND;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static PdfStatus of(String value) {
        for (PdfStatus status : values()) {
            if (status.value().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown pdf_status " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<PdfStatus> {
        public Converter() {
            super(PdfStatus.class);
        }
    }
}
