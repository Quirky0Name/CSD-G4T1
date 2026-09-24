package com.g4t1.storage.metadata;

public record PaperMetadata(
        String doi,
        String openalexId,
        String title,
        String journal,
        String issn,
        Integer publicationYear) {
}
