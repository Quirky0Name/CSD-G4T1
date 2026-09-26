package com.g4t1.storage.alert;

import java.util.List;

// the body of GET /internal/papers/{id}/alerts/change-keys: {"change_keys": [...]}
public record ChangeKeysResponse(List<String> changeKeys) {
}
