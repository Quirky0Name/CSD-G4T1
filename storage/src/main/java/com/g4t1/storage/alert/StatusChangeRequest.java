package com.g4t1.storage.alert;

import jakarta.validation.constraints.NotNull;

// the body of PATCH /alerts/{id}; AlertService rejects NEW, since an alert can't be reset
public record StatusChangeRequest(@NotNull AlertStatus status) {
}
