package com.g4t1.users.profile;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The questionnaire's lists (profile/options.json, kept equal to the frontend's options.ts by a
 * frontend test) and its rules. normalise checks a request and returns it as it is stored: text
 * trimmed, blank text null, absent lists empty. A broken rule is a 400 whose reason starts with the
 * field's name.
 */
@Component
public class ProfileOptions {

    public static final int MAX_INTERESTS = 5;
    public static final int MAX_INTEREST_LENGTH = 60;
    public static final int MAX_OCCUPATION_OTHER_LENGTH = 60;
    // UTF-16 code units, what String.length() and a browser's maxLength both count
    public static final int MAX_PREFERENCES_LENGTH = 500;
    public static final String OTHER = "Other";

    private record Lists(List<String> occupations, List<String> researchFields, List<String> workTypes) {
    }

    private final Lists lists;

    public ProfileOptions() {
        try (InputStream in = new ClassPathResource("profile/options.json").getInputStream()) {
            // the file is snake_case, like the API
            this.lists = JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .build()
                    .readValue(in, Lists.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ProfileRequest normalise(ProfileRequest request) {
        String occupation = request.occupation();
        if (occupation != null && !lists.occupations().contains(occupation)) {
            throw bad("occupation must be one of the listed occupations");
        }
        String occupationOther = blankToNull(request.occupationOther());
        if (occupationOther != null) {
            if (!OTHER.equals(occupation)) {
                throw bad("occupation_other is allowed only when occupation is Other");
            }
            checkText("occupation_other", occupationOther, MAX_OCCUPATION_OTHER_LENGTH, false);
        }
        List<String> researchFields = listed("research_fields", request.researchFields(), lists.researchFields(),
                "field");
        List<String> researchInterests = interests(request.researchInterests());
        List<String> workTypes = listed("work_types", request.workTypes(), lists.workTypes(), "work type");
        String responsePreferences = blankToNull(request.responsePreferences());
        if (responsePreferences != null) {
            checkText("response_preferences", responsePreferences, MAX_PREFERENCES_LENGTH, true);
        }
        return new ProfileRequest(occupation, occupationOther, researchFields, researchInterests, workTypes,
                responsePreferences);
    }

    private static List<String> listed(String field, List<String> values, List<String> allowed, String noun) {
        if (values == null) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null) {
                throw bad(field + " must not contain null");
            }
            if (!allowed.contains(value)) {
                throw bad(field + " must contain only listed values: " + value + " is not a listed " + noun);
            }
            if (!seen.add(value)) {
                throw bad(field + " must not contain " + value + " twice");
            }
        }
        return List.copyOf(values);
    }

    private static List<String> interests(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_INTERESTS) {
            throw bad("research_interests must have at most " + MAX_INTERESTS + " interests");
        }
        List<String> trimmed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (value == null) {
                throw bad("research_interests must not contain null");
            }
            String interest = value.strip();
            if (interest.isEmpty()) {
                throw bad("research_interests must not contain a blank interest");
            }
            checkText("research_interests", interest, MAX_INTEREST_LENGTH, false);
            if (!seen.add(interest.toLowerCase(Locale.ROOT))) {
                throw bad("research_interests must not contain " + interest + " twice");
            }
            trimmed.add(interest);
        }
        return List.copyOf(trimmed);
    }

    private static void checkText(String field, String text, int maxLength, boolean allowLineBreaksAndTabs) {
        if (text.length() > maxLength) {
            throw bad(field + " must be at most " + maxLength + " characters");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = allowLineBreaksAndTabs && (c == '\n' || c == '\r' || c == '\t');
            if (Character.isISOControl(c) && !allowed) {
                throw bad(field + " must not contain control characters");
            }
        }
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
