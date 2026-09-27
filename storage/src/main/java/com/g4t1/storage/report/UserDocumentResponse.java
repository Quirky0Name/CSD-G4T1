package com.g4t1.storage.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

// A report's document as the researcher sees it: the stored row without what's only Storage
// Management's or impact's (file_key, sha256) and without report_id, since it's nested in its report
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserDocumentResponse(
        Long id,
        DocumentKind kind,
        String doi,
        CrossrefStatus crossrefStatus,
        JsonNode crossrefRecord,
        Boolean updateToIncludesPaper,
        TextStatus textStatus,
        String text,
        boolean textTruncated,
        PdfStatus pdfStatus,
        String pdfSourceUrl,
        Instant createdAt,
        Instant pdfFetchedAt) {
}
