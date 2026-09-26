package com.g4t1.storage.alert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// the body of POST /alerts/{id}/notes
public record NewNoteRequest(@NotBlank @Size(max = 2000) String text) {
}
