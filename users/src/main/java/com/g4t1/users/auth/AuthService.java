package com.g4t1.users.auth;

import com.g4t1.users.profile.ProfileOptions;
import com.g4t1.users.profile.ProfileRequest;
import com.g4t1.users.profile.UserProfile;
import com.g4t1.users.profile.UserProfileRepository;
import com.g4t1.users.storage.StorageClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final TokenService tokens;
    private final StorageClient storage;
    private final UserProfileRepository profiles;
    private final ProfileOptions profileOptions;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();
    // checked when an email has no account, so that answer takes as long as a wrong password
    private final String hashForUnknownEmails = passwords.encode("no account has this password");

    public AuthService(UserRepository users, TokenService tokens, StorageClient storage,
                       UserProfileRepository profiles, ProfileOptions profileOptions) {
        this.users = users;
        this.tokens = tokens;
        this.storage = storage;
        this.profiles = profiles;
        this.profileOptions = profileOptions;
    }

    /**
     * The account and its questionnaire in one transaction, so neither is saved without the other.
     * The questionnaire is checked first: a bad one is a 400 even on a taken email.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        ProfileRequest answers = request.profile() == null ? null : profileOptions.normalise(request.profile());
        String email = normalised(request.email());
        if (users.existsByEmail(email)) {
            throw emailTaken();
        }
        User user = saveNew(new User(email, passwords.encode(request.password())));
        if (answers != null) {
            // straight to the repository: ProfileService.save's retry is for an account that already exists
            UserProfile profile = new UserProfile(user.getId());
            profile.replace(answers);
            profiles.saveAndFlush(profile);
        }
        return new AuthResponse(tokens.issue(user.getId()), UserResponse.from(user));
    }

    public AuthResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(normalised(request.email()));
        String hash = found.map(User::getPasswordHash).orElse(hashForUnknownEmails);
        boolean passwordMatches = passwords.matches(request.password(), hash);
        // one answer for an unknown email and a wrong password, so login never shows which emails have accounts
        if (found.isEmpty() || !passwordMatches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong email or password");
        }
        User user = found.get();
        String token = user.isAdmin() ? tokens.issueAdmin(user.getId()) : tokens.issue(user.getId());
        return new AuthResponse(token, UserResponse.from(user));
    }

    public void deleteAccount(UUID userId, DeleteAccountRequest request) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        if (!passwords.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Wrong password");
        }
        // Storage first: if clearing the papers and drafts fails, the account is still here to delete again
        try {
            storage.clearUser(userId);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Storage Management couldn't clear your account, so it wasn't deleted");
        }
        users.deleteIfPresent(userId);
    }

    // another registration of the same email can be saved between the check and this insert; the 409
    // is a runtime exception, so it rolls the transaction back
    private User saveNew(User user) {
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
    }

    private static ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
    }

    // one account per address whatever its letter case; ROOT so the server's locale can't change it
    private static String normalised(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
