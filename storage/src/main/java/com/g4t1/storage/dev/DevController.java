package com.g4t1.storage.dev;

import com.g4t1.storage.snapshot.SnapshotResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Demo and testing tools, on for everyone for now; take out before a real deployment.
// User token, and only on the caller's own papers.
@RestController
@RequestMapping("/dev/papers/{paperId}")
public class DevController {

    private final DevService dev;

    public DevController(DevService dev) {
        this.dev = dev;
    }

    // change: retraction, correction, erratum or expression_of_concern
    @PostMapping("/undo-change")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotResponse undoChange(@AuthenticationPrincipal UUID userId, @PathVariable UUID paperId,
                                       @RequestParam String change) {
        return dev.undoChange(userId, paperId, change);
    }

    @DeleteMapping("/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearHistory(@AuthenticationPrincipal UUID userId, @PathVariable UUID paperId) {
        dev.clearHistory(userId, paperId);
    }

    // the paper and everything that refers to it: snapshots, alerts and notes, reports and documents, PDFs
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePaper(@AuthenticationPrincipal UUID userId, @PathVariable UUID paperId) {
        dev.deletePaper(userId, paperId);
    }
}
