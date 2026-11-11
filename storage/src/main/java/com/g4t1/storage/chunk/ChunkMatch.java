package com.g4t1.storage.chunk;

import java.time.Instant;

// One search result. report_id and source_date come from the source row: the alert's report and
// detected_at, the report's id and evaluated_at, the document's report and created_at; a profile chunk
// has no report and its own created_at. score is 1 - cosine distance.
public record ChunkMatch(
        SourceType sourceType,
        String sourceId,
        int chunkIndex,
        Integer page,
        Long reportId,
        Instant sourceDate,
        String content,
        double score) {
}
