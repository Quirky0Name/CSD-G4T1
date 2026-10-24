package com.g4t1.storage.draft;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Controller for RE; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
public class InternalDraftController {

    private final DraftService drafts;

    public InternalDraftController(DraftService drafts) {
        this.drafts = drafts;
    }

    // the researcher's draft for the project this tracked paper is in
    @GetMapping("/internal/papers/{paperId}/draft")
    public ResponseEntity<byte[]> draft(@PathVariable UUID paperId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(drafts.pdfForPaper(paperId));
    }
}
