package com.g4t1.users.profile;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

// A saved profile; the lists are always arrays and the rest a string or null
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfileResponse(
        String occupation,
        String occupationOther,
        List<String> researchFields,
        List<String> researchInterests,
        List<String> workTypes,
        String responsePreferences) {

    static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(profile.getOccupation(), profile.getOccupationOther(),
                profile.getResearchFields(), profile.getResearchInterests(), profile.getWorkTypes(),
                profile.getResponsePreferences());
    }
}
