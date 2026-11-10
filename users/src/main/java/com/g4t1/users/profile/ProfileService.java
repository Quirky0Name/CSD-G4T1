package com.g4t1.users.profile;

import com.g4t1.users.auth.AccountGoneException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ProfileService {

    private final UserProfileRepository profiles;
    private final ProfileOptions options;

    public ProfileService(UserProfileRepository profiles, ProfileOptions options) {
        this.profiles = profiles;
        this.options = options;
    }

    @Transactional(readOnly = true)
    public Optional<ProfileResponse> find(UUID userId) {
        return profiles.findById(userId).map(ProfileResponse::from);
    }

    /**
     * Replaces every answer, creating the row when there is none. Not @Transactional, so two first
     * saves at once can both succeed: the one whose insert loses to the other's tries once more and
     * updates that row. A second failure means the account is gone (the foreign key refuses it).
     */
    public ProfileResponse save(UUID userId, ProfileRequest request) {
        ProfileRequest answers = options.normalise(request);
        try {
            return write(userId, answers);
        } catch (DataIntegrityViolationException first) {
            try {
                return write(userId, answers);
            } catch (DataIntegrityViolationException again) {
                throw new AccountGoneException();
            }
        }
    }

    private ProfileResponse write(UUID userId, ProfileRequest answers) {
        UserProfile profile = profiles.findById(userId).orElseGet(() -> new UserProfile(userId));
        profile.replace(answers);
        return ProfileResponse.from(profiles.saveAndFlush(profile));
    }
}
