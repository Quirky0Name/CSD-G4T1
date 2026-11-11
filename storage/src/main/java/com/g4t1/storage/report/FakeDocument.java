package com.g4t1.storage.report;

import tools.jackson.databind.JsonNode;

// One document of the dev mode admin's fake report. pdf and pdfSourceUrl are null for a notice.
public record FakeDocument(
        DocumentKind kind,
        String doi,
        CrossrefStatus crossrefStatus,
        JsonNode crossrefRecord,
        Boolean updateToIncludesPaper,
        TextStatus textStatus,
        String text,
        boolean textTruncated,
        JsonNode links,
        byte[] pdf,
        String pdfSourceUrl) {
}
