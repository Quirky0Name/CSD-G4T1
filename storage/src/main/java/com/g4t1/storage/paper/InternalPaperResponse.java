package com.g4t1.storage.paper;

import java.util.UUID;

// the GET /internal/papers shape in docs/CONTRACTS.md, kept apart from PaperResponse
// so the frontend's response can change without breaking Updating
public record InternalPaperResponse(UUID id, UUID ownerId, String doi, String issn) {

    static InternalPaperResponse from(Paper paper) {
        return new InternalPaperResponse(paper.getId(), paper.getOwnerId(), paper.getDoi(), paper.getIssn());
    }
}
