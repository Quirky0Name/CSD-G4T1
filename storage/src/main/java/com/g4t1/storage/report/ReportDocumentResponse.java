package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

// the entire row as stored, nulls written out: Research Evaluation uses this, not what it sent
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReportDocumentResponse(
        Long id,
        Long reportId,
        DocumentKind kind,
        String doi,
        CrossrefStatus crossrefStatus,
        JsonNode crossrefRecord,
        Boolean updateToIncludesPaper,
        TextStatus textStatus,
        String text,
        boolean textTruncated,
        PdfStatus pdfStatus,
        String fileKey,
        String sha256,
        String pdfSourceUrl,
        Instant createdAt,
        Instant pdfFetchedAt) {
}
