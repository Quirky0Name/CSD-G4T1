package com.g4t1.users.profile;

import java.util.List;

/**
 * The questionnaire's answers, for register and for PUT /auth/me/profile. Every field is optional
 * and nothing is checked here: Bean Validation would run before trimming, so ProfileOptions checks
 * the lengths, the listed values, duplicates and control characters, and normalises the answers.
 */
public record ProfileRequest(
        String occupation,
        String occupationOther,
        List<String> researchFields,
        List<String> researchInterests,
        List<String> workTypes,
        String responsePreferences) {
}
