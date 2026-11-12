package com.g4t1.storage.chunk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// what a chunk's text came from. A feedback chunk is a report's evaluation with the researcher's feedback
// on it, keyed by the report like the report's own chunks
public enum SourceType {
    ALERT, REPORT, DOCUMENT, FEEDBACK;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static SourceType of(String value) {
        for (SourceType type : values()) {
            if (type.value().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown source_type " + value);
    }
}
