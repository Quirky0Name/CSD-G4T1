package com.g4t1.users.profile;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A researcher's profile for Research Evaluation, which tailors impact's text to it. Service tokens
 * only, and reachable only on the compose network: neither Caddy nor the Vite proxy forwards
 * /internal. An unknown user is the same 404 as an account with no profile.
 */
@RestController
public class InternalProfileController {

    private final ProfileService profiles;

    public InternalProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/internal/users/{userId}/profile")
    public ProfileResponse read(@PathVariable UUID userId) {
        return profiles.find(userId)
                .orElseThrow(() -> ProfileService.noProfile(userId));
    }
}
