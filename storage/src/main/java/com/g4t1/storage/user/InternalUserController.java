package com.g4t1.storage.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// service-token only; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
@RequestMapping("/internal/users")
public class InternalUserController {

    // User Management calls this before deleting an account, so the user's papers and drafts go with it
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable UUID userId) {
    }
}
