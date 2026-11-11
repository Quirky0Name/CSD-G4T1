package com.g4t1.users.profile;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.List;
import java.util.UUID;

// One researcher's answers to the questionnaire; the account's id is the key, so there's at most one
@Entity
@Table(name = "user_profiles")
public class UserProfile {

    @Id
    private UUID userId;

    private String occupation;

    private String occupationOther;

    @Convert(converter = StringListConverter.class)
    private List<String> researchFields = List.of();

    @Convert(converter = StringListConverter.class)
    private List<String> researchInterests = List.of();

    @Convert(converter = StringListConverter.class)
    private List<String> workTypes = List.of();

    private String responsePreferences;

    protected UserProfile() {
    }

    public UserProfile(UUID userId) {
        this.userId = userId;
    }

    // every answer at once, already checked and normalised by ProfileOptions
    public void replace(ProfileRequest answers) {
        this.occupation = answers.occupation();
        this.occupationOther = answers.occupationOther();
        this.researchFields = answers.researchFields();
        this.researchInterests = answers.researchInterests();
        this.workTypes = answers.workTypes();
        this.responsePreferences = answers.responsePreferences();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getOccupation() {
        return occupation;
    }

    public String getOccupationOther() {
        return occupationOther;
    }

    public List<String> getResearchFields() {
        return researchFields;
    }

    public List<String> getResearchInterests() {
        return researchInterests;
    }

    public List<String> getWorkTypes() {
        return workTypes;
    }

    public String getResponsePreferences() {
        return responsePreferences;
    }
}
