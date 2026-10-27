package com.g4t1.users.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// A token outlives a deleted account by up to a day; a 401 makes the frontend sign it out
public class AccountGoneException extends ResponseStatusException {

    public AccountGoneException() {
        super(HttpStatus.UNAUTHORIZED, "Your account no longer exists");
    }
}
