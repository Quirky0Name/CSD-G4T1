package com.g4t1.storage.report;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

// The body of POST /internal/documents: what investigation fetched for one DOI of a report.
// crossref_record is stored as-is, so storage doesn't break when Crossref's shape changes.
public record NewReportDocumentRequest(
        @NotNull Long reportId,
        @NotNull DocumentKind kind,
        @NotBlank @Size(max = 255) String doi,
        @NotNull CrossrefStatus crossrefStatus,
        JsonNode crossrefRecord,
        Boolean updateToIncludesPaper,
        @NotNull TextStatus textStatus,
        String text,
        @NotNull Boolean textTruncated) {
}
