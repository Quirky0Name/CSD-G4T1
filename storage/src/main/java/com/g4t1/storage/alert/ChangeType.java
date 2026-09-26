package com.g4t1.storage.alert;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

// the kinds of change Research Evaluation classifies; OTHER is a Crossref notice type it doesn't classify yet
public enum ChangeType {
    RETRACTION, CORRECTION, ERRATUM, EXPRESSION_OF_CONCERN, DOAJ_DELISTING, OTHER;

    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    // strict: only the exact lowercase value, anything else is a 400
    @JsonCreator
    public static ChangeType of(String value) {
        for (ChangeType type : values()) {
            if (type.value().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown change_type " + value);
    }

    @jakarta.persistence.Converter
    public static class Converter extends LowercaseEnumConverter<ChangeType> {
        public Converter() {
            super(ChangeType.class);
        }
    }
}
