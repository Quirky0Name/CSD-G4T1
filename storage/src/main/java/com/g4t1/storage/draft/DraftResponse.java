package com.g4t1.storage.research;

import java.time.Instant;
import java.util.UUID;

// folder_id is null for the "no folder" project
public record ResearchPaperResponse(UUID id, UUID folderId, String filename, Instant uploadedAt) {

    static ResearchPaperResponse from(ResearchPaper researchPaper) {
        return new ResearchPaperResponse(
                researchPaper.getId(),
                researchPaper.getFolderId(),
                researchPaper.getFilename(),
                researchPaper.getUploadedAt());
    }
}
