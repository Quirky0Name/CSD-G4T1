package com.g4t1.storage.draft;

import java.time.Instant;
import java.util.UUID;

// project_id is null for the default project
public record DraftResponse(UUID id, UUID projectId, String filename, Instant uploadedAt) {

    static DraftResponse from(Draft draft) {
        return new DraftResponse(
                draft.getId(),
                draft.getProjectId(),
                draft.getFilename(),
                draft.getUploadedAt());
    }
}
