package com.g4t1.storage.alert;

import jakarta.persistence.AttributeConverter;

import java.util.Locale;

// stores an enum as its lowercase name, the same value the API uses (e.g. 'new', 'doaj_delisting')
abstract class LowercaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;

    LowercaseEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public E convertToEntityAttribute(String column) {
        return column == null ? null : Enum.valueOf(type, column.toUpperCase(Locale.ROOT));
    }
}
