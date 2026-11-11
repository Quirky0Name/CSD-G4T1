package com.g4t1.storage.chunk;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class ChunkService {

    private final ChunkRepository chunks;

    public ChunkService(ChunkRepository chunks) {
        this.chunks = chunks;
    }

    // Replaces every chunk of the source in one transaction. paper_id is copied from the source row,
    // never taken from the caller. A profile is never 404: its user lives in User Management.
    @Transactional
    public void store(StoreChunksRequest request) {
        SourceType type = request.sourceType();
        if (type == SourceType.PROFILE) {
            chunks.replace(type, parseUuid(request.sourceId()), null, request.embedModel(), request.chunks());
            return;
        }
        long sourceId = parseId(request.sourceId());
        UUID paperId = chunks.paperOf(type, sourceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + type.value() + " " + sourceId));
        chunks.replace(type, sourceId, paperId, request.embedModel(), request.chunks());
    }

    // every chunk of the paper of that source type is a candidate: there's no date, order or exclusion
    // filter, since a report's chunks are only written once impact has assessed it. Ranked by similarity only.
    @Transactional(readOnly = true)
    public List<ChunkMatch> search(SearchChunksRequest request) {
        if (!chunks.paperExists(request.paperId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + request.paperId());
        }
        return chunks.search(request.paperId(), request.sourceType(), request.embedding(), request.limit());
    }

    // a user's profile chunks, which have no foreign key to cascade from
    @Transactional
    public void deleteProfile(UUID userId) {
        chunks.deleteProfile(userId);
    }

    private static long parseId(String sourceId) {
        try {
            return Long.parseLong(sourceId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_id must be a decimal id");
        }
    }

    private static UUID parseUuid(String sourceId) {
        try {
            return UUID.fromString(sourceId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_id must be a user's uuid");
        }
    }
}
