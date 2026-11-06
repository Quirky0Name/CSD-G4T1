package com.g4t1.storage.admin;

import tools.jackson.databind.JsonNode;

// Raw JSON, because binding to Boolean and Integer would quietly accept "false" and 1.5.
// AdminSnapshotService checks each field against docs/CONTRACTS.md.
public record SnapshotEditRequest(
        JsonNode isRetracted,
        JsonNode inDoaj,
        JsonNode citedByCount,
        JsonNode removedCrossrefUpdateTypes) {
}
