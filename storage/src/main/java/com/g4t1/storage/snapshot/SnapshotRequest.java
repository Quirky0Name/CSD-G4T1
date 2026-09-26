package com.g4t1.storage.snapshot;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

// The snapshot Updating sends (docs/CONTRACTS.md, "Snapshot fields"). The three
// JsonNode fields are stored as-is, so storage doesn't break when their inner shape changes.
public record SnapshotRequest(
        @NotBlank String doi,
        @NotNull Instant fetchedAt,
        String openalexId,
        String title,
        Integer publicationYear,
        Boolean isRetracted,
        JsonNode crossrefUpdates,
        Boolean inDoaj,
        String journalSourceId,
        String journalSourceType,
        String journal,
        String issnL,
        String publisher,
        JsonNode authors,
        Integer citedByCount,
        @NotNull JsonNode sourceStatus) {
}
