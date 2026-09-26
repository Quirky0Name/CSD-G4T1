package com.g4t1.storage.snapshot;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// service-token only; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
@RequestMapping("/internal/papers/{paperId}/background-info")
public class SnapshotController {

    private final SnapshotService snapshots;

    public SnapshotController(SnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    // Updating sends one per tracked paper per poll, even when nothing changed
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse store(@PathVariable UUID paperId, @Valid @RequestBody SnapshotRequest body) {
        return snapshots.store(paperId, body);
    }

    @GetMapping("/history")
    public Map<String, List<SnapshotResponse>> history(@PathVariable UUID paperId,
                                                       @RequestParam(name = "after_id", required = false) Long afterId,
                                                       @RequestParam(required = false) Integer limit) {
        return Map.of("snapshots", snapshots.history(paperId, afterId, limit));
    }
}
