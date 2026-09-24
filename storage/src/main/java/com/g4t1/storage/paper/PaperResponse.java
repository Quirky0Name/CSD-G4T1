package com.g4t1.storage.paper;

import java.time.Instant;
import java.util.UUID;

public record PaperResponse(
        UUID id,
        UUID folderId,
        String doi,
        String openalexId,
        String title,
        String journal,
        String issn,
        Integer publicationYear,
        boolean fileAvailable,
        Instant createdAt) {

    static PaperResponse from(Paper paper) {
        return new PaperResponse(
                paper.getId(),
                paper.getFolderId(),
                paper.getDoi(),
                paper.getOpenalexId(),
                paper.getTitle(),
                paper.getJournal(),
                paper.getIssn(),
                paper.getPublicationYear(),
                paper.hasFile(),
                paper.getCreatedAt());
    }
}
