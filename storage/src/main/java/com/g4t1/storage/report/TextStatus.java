package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// how Research Evaluation's Europe PMC text lookup of the document's DOI went
public enum TextStatus {
    OK, NOT_INDEXED, NOT_OPEN_ACCESS, ERROR;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static TextStatus of(String value) {
        for (TextStatus status : values()) {
            if (status.value().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown text_status " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<TextStatus> {
        public Converter() {
            super(TextStatus.class);
        }
    }
}
