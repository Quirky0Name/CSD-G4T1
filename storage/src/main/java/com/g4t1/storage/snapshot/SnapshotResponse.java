package com.g4t1.storage.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

// Updating parses every field of a history row, so nulls are always written out
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SnapshotResponse(
        Long snapshotId,
        UUID paperId,
        String doi,
        Instant fetchedAt,
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
        JsonNode sourceStatus) {
}
