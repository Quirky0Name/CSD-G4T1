package com.g4t1.storage.dev;

import com.g4t1.storage.snapshot.SnapshotResponse;
import org.springframework.context.annotation.Profile;
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

// Local testing only (dev profile). User token, and only on the caller's own papers.
@RestController
@Profile("dev")
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
}
