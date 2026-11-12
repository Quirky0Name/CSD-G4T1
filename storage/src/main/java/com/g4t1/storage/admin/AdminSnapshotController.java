package com.g4t1.storage.admin;

import com.g4t1.storage.snapshot.SnapshotResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Dev mode only, admin token only (SecurityConfig), on any researcher's paper
@RestController
@ConditionalOnBooleanProperty("storage.dev-mode")
@RequestMapping("/admin/papers/{paperId}/snapshots")
public class AdminSnapshotController {

    private final AdminSnapshotService edits;

    public AdminSnapshotController(AdminSnapshotService edits) {
        this.edits = edits;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse edit(@PathVariable UUID paperId, @RequestBody SnapshotEditRequest body) {
        return edits.edit(paperId, body);
    }
}
