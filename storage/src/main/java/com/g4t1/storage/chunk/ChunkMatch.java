package com.g4t1.storage.chunk;

import java.time.Instant;
import java.util.UUID;

// One search result. report_id and source_date come from the source row: the alert's report and
// detected_at, the report's id and evaluated_at, the document's report and created_at, and for feedback
// the report's id and feedback_updated_at. paper_id and paper_title are the chunk's paper, which for
// feedback can be another of the owner's papers. score is 1 - cosine distance.
public record ChunkMatch(
        SourceType sourceType,
        String sourceId,
        int chunkIndex,
        Integer page,
        Long reportId,
        UUID paperId,
        String paperTitle,
        Instant sourceDate,
        String content,
        double score) {
}
