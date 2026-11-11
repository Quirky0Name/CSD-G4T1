package com.g4t1.storage.chunk;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// The body of POST /internal/chunks/search: the paper's chunks of one source type nearest to embedding.
// With profile, the paper owner's profile chunks.
public record SearchChunksRequest(
        @NotNull UUID paperId,
        @NotNull @Size(min = ChunkRepository.DIMENSIONS, max = ChunkRepository.DIMENSIONS)
        List<@NotNull Double> embedding,
        @NotNull SourceType sourceType,
        @NotNull @Min(1) @Max(20) Integer limit) {
}
