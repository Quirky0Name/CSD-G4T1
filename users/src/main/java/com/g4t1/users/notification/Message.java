package com.g4t1.users.notification;

// A notification's text; only email has the subject
public record Message(String subject, String text) {
}
