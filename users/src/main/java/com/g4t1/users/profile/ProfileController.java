package com.g4t1.users.profile;

import com.g4t1.users.auth.AccountGoneException;
import com.g4t1.users.auth.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A researcher's own profile. Both check the account still exists, so a token whose account was
 * deleted is a 401, which signs the frontend out, not the 404 of an account with no profile.
 */
@RestController
@RequestMapping("/auth/me/profile")
public class ProfileController {

    private final ProfileService profiles;
    private final UserRepository users;

    public ProfileController(ProfileService profiles, UserRepository users) {
        this.profiles = profiles;
        this.users = users;
    }

    @GetMapping
    public ProfileResponse read(@AuthenticationPrincipal UUID userId) {
        requireAccount(userId);
        return profiles.find(userId)
                .orElseThrow(() -> ProfileService.noProfile(userId));
    }

    // the whole profile: a field left out or null is cleared, and {} clears every answer but keeps the row
    @PutMapping
    public ProfileResponse replace(@AuthenticationPrincipal UUID userId, @RequestBody ProfileRequest request) {
        requireAccount(userId);
        return profiles.save(userId, request);
    }

    private void requireAccount(UUID userId) {
        if (!users.existsById(userId)) {
            throw new AccountGoneException();
        }
    }
}
