package com.g4t1.storage.admin;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.g4t1.storage.paper.PaperResponse;
import com.g4t1.storage.snapshot.SnapshotResponse;

import java.util.UUID;

// A paper in the GET /papers shape, plus whose it is and its latest snapshot (null before its first poll)
public record AdminPaperResponse(
        @JsonUnwrapped PaperResponse paper,
        UUID ownerId,
        SnapshotResponse latestSnapshot) {
}
