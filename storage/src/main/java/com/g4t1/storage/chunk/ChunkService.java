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
    // never taken from the caller.
    @Transactional
    public void store(StoreChunksRequest request) {
        SourceType type = request.sourceType();
        long sourceId = parseId(request.sourceId());
        UUID paperId = chunks.paperOf(type, sourceId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + noun(type) + " " + sourceId));
        chunks.replace(type, sourceId, paperId, request.embedModel(), request.chunks());
    }

    // Every chunk of that source type is a candidate, of the paper or, for feedback, of every paper of
    // its owner: there's no date, order or exclusion filter, since a report's chunks are only written once
    // impact has assessed it and feedback only on an assessed report. Ranked by similarity only.
    @Transactional(readOnly = true)
    public List<ChunkMatch> search(SearchChunksRequest request) {
        if (!chunks.paperExists(request.paperId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + request.paperId());
        }
        return chunks.search(request.paperId(), request.sourceType(), request.embedding(), request.limit());
    }

    // a feedback chunk's source is its report
    private static String noun(SourceType type) {
        return type == SourceType.FEEDBACK ? "report" : type.value();
    }

    private static long parseId(String sourceId) {
        try {
            return Long.parseLong(sourceId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_id must be a decimal id");
        }
    }
}
