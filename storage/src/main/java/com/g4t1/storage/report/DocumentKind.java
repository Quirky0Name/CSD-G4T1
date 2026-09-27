package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.alert.LowercaseEnumConverter;

import java.util.Locale;

// what a report document is: a notice, a newer version under another DOI, or the paper's own DOI
// downloaded again (the current copy). Only the last two get a PDF
public enum DocumentKind {
    NOTICE, NEW_VERSION, CURRENT_VERSION;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static DocumentKind of(String value) {
        for (DocumentKind kind : values()) {
            if (kind.value().equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown kind " + value);
    }

    public boolean hasPdf() {
        return this != NOTICE;
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<DocumentKind> {
        public Converter() {
            super(DocumentKind.class);
        }
    }
}
