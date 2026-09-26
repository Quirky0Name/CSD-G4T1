package com.g4t1.storage.alert;

import java.time.Instant;

public record AlertNoteResponse(String text, Instant createdAt) {

    static AlertNoteResponse from(AlertNote note) {
        return new AlertNoteResponse(note.getText(), note.getCreatedAt());
    }
}
