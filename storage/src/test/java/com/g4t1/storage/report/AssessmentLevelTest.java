package com.g4t1.storage.report;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssessmentLevelTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AssessmentLevel.Converter converter = new AssessmentLevel.Converter();

    @Test
    void readsAndWritesItsLowercaseValues() {
        for (AssessmentLevel level : AssessmentLevel.values()) {
            String value = level.name().toLowerCase();
            assertThat(json.writeValueAsString(level)).isEqualTo("\"" + value + "\"");
            assertThat(json.readValue("\"" + value + "\"", AssessmentLevel.class)).isEqualTo(level);
            assertThat(converter.convertToDatabaseColumn(level)).isEqualTo(value);
            assertThat(converter.convertToEntityAttribute(value)).isEqualTo(level);
        }
        assertThat(AssessmentLevel.values()).containsExactly(
                AssessmentLevel.NONE, AssessmentLevel.LOW, AssessmentLevel.MEDIUM, AssessmentLevel.HIGH);
    }

    @Test
    void nullStaysNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejectsAnUnknownOrUppercaseValue() {
        assertThatThrownBy(() -> AssessmentLevel.of("severe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssessmentLevel.of("HIGH")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> json.readValue("\"critical\"", AssessmentLevel.class)).isInstanceOf(Exception.class);
    }
}
