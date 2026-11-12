package com.g4t1.users.profile;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

// A list of answers as a JSON array in a text column, so V4 runs on Postgres and on the tests' H2.
// A null column reads as an empty list.
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        return values == null ? null : JSON.writeValueAsString(values);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        return column == null ? List.of() : List.copyOf(JSON.readValue(column, STRINGS));
    }
}
