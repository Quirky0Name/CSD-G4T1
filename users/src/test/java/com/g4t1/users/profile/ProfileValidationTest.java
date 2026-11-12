package com.g4t1.users.profile;

import com.g4t1.users.auth.User;
import com.g4t1.users.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ProfileService saves and reads a profile by the questionnaire's rules (docs/CONTRACTS.md); the
// context starting at all shows V4 applies on H2 and matches the entity under ddl-auto: validate
@SpringBootTest
@ActiveProfiles("test")
class ProfileValidationTest {

    @Autowired
    ProfileService profiles;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void newAccount() {
        userId = users.save(new User(UUID.randomUUID() + "@example.com", "hash")).getId();
    }

    @Test
    void theMigrationMadeTheProfileTable() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = 'users' and table_name = 'user_profiles'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder("user_id", "occupation", "occupation_other",
                "research_fields", "research_interests", "work_types", "response_preferences");
    }

    @Test
    void aSavedProfileReadsBackWithItsListsInOrder() {
        ProfileResponse saved = profiles.save(userId, new ProfileRequest("PhD student", null,
                List.of("Psychology", "Medicine"), List.of("vaccine safety", "Ageing"),
                List.of("Clinical", "Data or software"), "Keep it brief."));

        ProfileResponse read = profiles.find(userId).orElseThrow();

        assertThat(read).isEqualTo(saved);
        assertThat(read.occupation()).isEqualTo("PhD student");
        assertThat(read.occupationOther()).isNull();
        assertThat(read.researchFields()).containsExactly("Psychology", "Medicine");
        assertThat(read.researchInterests()).containsExactly("vaccine safety", "Ageing");
        assertThat(read.workTypes()).containsExactly("Clinical", "Data or software");
        assertThat(read.responsePreferences()).isEqualTo("Keep it brief.");
    }

    @Test
    void aStoredNullListReadsAsEmpty() {
        jdbc.update("insert into users.user_profiles (user_id) values (?)", userId);

        ProfileResponse read = profiles.find(userId).orElseThrow();

        assertThat(read.researchFields()).isEmpty();
        assertThat(read.researchInterests()).isEmpty();
        assertThat(read.workTypes()).isEmpty();
        assertThat(read.occupation()).isNull();
    }

    @Test
    void anAccountWithNoRowHasNoProfile() {
        assertThat(profiles.find(userId)).isEmpty();
    }

    @Test
    void absentListsAreEmptyAndAnEmptyProfileIsStillSaved() {
        ProfileResponse saved = profiles.save(userId, new ProfileRequest(null, null, null, null, null, null));

        assertThat(saved).isEqualTo(new ProfileResponse(null, null, List.of(), List.of(), List.of(), null));
        assertThat(profiles.find(userId)).contains(saved);
    }

    @Test
    void savingAgainReplacesEveryAnswer() {
        profiles.save(userId, new ProfileRequest("Other", "Librarian", List.of("Medicine"), List.of("a"),
                List.of("Clinical"), "Short."));

        ProfileResponse saved = profiles.save(userId, new ProfileRequest(null, null, null, List.of("b"), null, null));

        assertThat(saved).isEqualTo(new ProfileResponse(null, null, List.of(), List.of("b"), List.of(), null));
        assertThat(jdbc.queryForObject("select count(*) from users.user_profiles where user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void textIsTrimmedAndBlankTextIsNull() {
        ProfileResponse saved = profiles.save(userId, new ProfileRequest("Other", "  Librarian  ", null,
                List.of("  vaccine safety "), null, "  Brief, please. \n"));
        assertThat(saved.occupationOther()).isEqualTo("Librarian");
        assertThat(saved.researchInterests()).containsExactly("vaccine safety");
        assertThat(saved.responsePreferences()).isEqualTo("Brief, please.");

        ProfileResponse blank = profiles.save(userId, new ProfileRequest("Other", "   ", null, null, null, " \n\t "));
        assertThat(blank.occupationOther()).isNull();
        assertThat(blank.responsePreferences()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    void validAnswersAreSaved(String name, ProfileRequest request) {
        assertThat(profiles.save(userId, request)).isNotNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void invalidAnswersAreABadRequestNamingTheFieldAndSaveNothing(String name, ProfileRequest request, String field) {
        assertThatThrownBy(() -> profiles.save(userId, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).startsWith(field);
                });
        assertThat(profiles.find(userId)).isEmpty();
    }

    static Stream<Arguments> valid() {
        Stream<Arguments> occupations = Stream.of("Undergraduate student", "Master's student", "PhD student",
                        "Postdoctoral researcher", "Faculty or principal investigator", "Research staff", "Clinician",
                        "Industry researcher", "Other")
                .map(occupation -> Arguments.of("occupation " + occupation,
                        new ProfileRequest(occupation, null, null, null, null, null)));
        return Stream.concat(occupations, Stream.of(
                Arguments.of("other with 60 characters", occupationOther("x".repeat(60))),
                Arguments.of("other with no text", new ProfileRequest("Other", null, null, null, null, null)),
                Arguments.of("listed fields", fields("Arts and Humanities", "Economics, Econometrics and Finance")),
                Arguments.of("5 interests", interests("a", "b", "c", "d", "e")),
                Arguments.of("a 60-character interest", interests("x".repeat(60))),
                Arguments.of("listed work types", workTypes("Theoretical", "Other")),
                Arguments.of("500 ASCII characters", preferences("x".repeat(500))),
                Arguments.of("500 code units with emoji", preferences("😀".repeat(250))),
                Arguments.of("newlines and tabs", preferences("First.\nThen\tthis.\r\nDone."))));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("an unlisted occupation", new ProfileRequest("Astronaut", null, null, null, null, null),
                        "occupation"),
                Arguments.of("an occupation in the wrong case", new ProfileRequest("phd student", null, null, null, null,
                        null), "occupation"),
                Arguments.of("other with 61 characters", occupationOther("x".repeat(61)), "occupation_other"),
                Arguments.of("other text with another occupation", new ProfileRequest("PhD student", "Librarian", null,
                        null, null, null), "occupation_other"),
                Arguments.of("other text with no occupation", new ProfileRequest(null, "Librarian", null, null, null,
                        null), "occupation_other"),
                Arguments.of("a control character in other", occupationOther("Libr\u0007arian"), "occupation_other"),
                Arguments.of("an unlisted field", fields("Astrology"), "research_fields"),
                Arguments.of("a field in the wrong case", fields("medicine"), "research_fields"),
                Arguments.of("a duplicate field", fields("Medicine", "Medicine"), "research_fields"),
                Arguments.of("a null field", fields((String) null), "research_fields"),
                Arguments.of("6 interests", interests("a", "b", "c", "d", "e", "f"), "research_interests"),
                Arguments.of("a 61-character interest", interests("x".repeat(61)), "research_interests"),
                Arguments.of("a blank interest", interests("   "), "research_interests"),
                Arguments.of("duplicate interests by case", interests("Ageing", "ageing"), "research_interests"),
                Arguments.of("a control character in an interest", interests("a\u0000b"), "research_interests"),
                Arguments.of("a null interest", interests((String) null), "research_interests"),
                Arguments.of("an unlisted work type", workTypes("Fieldwork"), "work_types"),
                Arguments.of("a duplicate work type", workTypes("Clinical", "Clinical"), "work_types"),
                Arguments.of("a null work type", workTypes((String) null), "work_types"),
                Arguments.of("501 ASCII characters", preferences("x".repeat(501)), "response_preferences"),
                Arguments.of("501 code units with emoji", preferences("😀".repeat(250) + "x"), "response_preferences"),
                Arguments.of("U+0000 in preferences", preferences("Brief\u0000please"), "response_preferences"),
                Arguments.of("another control character in preferences", preferences("Brief\u001Bplease"),
                        "response_preferences"));
    }

    private static ProfileRequest occupationOther(String text) {
        return new ProfileRequest("Other", text, null, null, null, null);
    }

    private static ProfileRequest fields(String... values) {
        return new ProfileRequest(null, null, list(values), null, null, null);
    }

    private static ProfileRequest interests(String... values) {
        return new ProfileRequest(null, null, null, list(values), null, null);
    }

    private static ProfileRequest workTypes(String... values) {
        return new ProfileRequest(null, null, null, null, list(values), null);
    }

    private static ProfileRequest preferences(String text) {
        return new ProfileRequest(null, null, null, null, null, text);
    }

    // List.of refuses nulls, and a null element is one of the cases
    private static List<String> list(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
