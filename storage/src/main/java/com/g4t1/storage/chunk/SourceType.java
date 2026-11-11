package com.g4t1.storage.chunk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// what a chunk's text came from. A profile's chunks belong to a user, not a paper
public enum SourceType {
    ALERT, REPORT, DOCUMENT, PROFILE;

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
