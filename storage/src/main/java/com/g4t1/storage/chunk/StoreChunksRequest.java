package com.g4t1.storage.chunk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// The body of PUT /internal/chunks: every chunk of one source, replacing the ones stored. source_id is
// the alert, report or document id in decimal, or the user's uuid for a profile. An empty list deletes them.
public record StoreChunksRequest(
        @NotNull SourceType sourceType,
        @NotBlank String sourceId,
        @NotBlank @Size(max = 64) String embedModel,
        @NotNull List<@NotNull @Valid Chunk> chunks) {

    // one chunk; its chunk_index is its position in the list. Storage never looks inside content
    public record Chunk(
            @NotBlank String content,
            @Min(1) Integer page,
            @NotNull @Size(min = ChunkRepository.DIMENSIONS, max = ChunkRepository.DIMENSIONS)
            List<@NotNull Double> embedding) {
    }
}
