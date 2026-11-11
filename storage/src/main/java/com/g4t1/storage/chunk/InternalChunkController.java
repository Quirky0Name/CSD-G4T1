package com.g4t1.storage.chunk;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Research Evaluation stores and searches embedded text here; service-token only (SecurityConfig)
@RestController
@RequestMapping("/internal/chunks")
public class InternalChunkController {

    private final ChunkService chunks;

    public InternalChunkController(ChunkService chunks) {
        this.chunks = chunks;
    }

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void store(@Valid @RequestBody StoreChunksRequest request) {
        chunks.store(request);
    }
}
