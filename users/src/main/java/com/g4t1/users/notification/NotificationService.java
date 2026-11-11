package com.g4t1.users.notification;

import com.g4t1.users.auth.AccountGoneException;
import com.g4t1.users.auth.User;
import com.g4t1.users.auth.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationPreferencesRepository preferences;
    private final UserRepository users;

    public NotificationService(NotificationPreferencesRepository preferences, UserRepository users) {
        this.preferences = preferences;
        this.users = users;
    }

    // the account is read too, for the address; a deleted account is a 401, which signs the frontend out
    @Transactional(readOnly = true)
    public NotificationPreferencesResponse find(UUID userId) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        return NotificationPreferencesResponse.from(preferences.findById(userId).orElseThrow(), user.getEmail());
    }

    @Transactional
    public NotificationPreferencesResponse replaceEmail(UUID userId, EmailRequest request) {
        User user = users.findById(userId).orElseThrow(AccountGoneException::new);
        NotificationPreferences prefs = preferences.findById(userId).orElseThrow();
        prefs.replaceEmail(request);
        return NotificationPreferencesResponse.from(preferences.save(prefs), user.getEmail());
    }
}
