package com.g4t1.storage.research;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Controller for RE; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
public class InternalResearchPaperController {

    private final ResearchPaperService researchPapers;

    public InternalResearchPaperController(ResearchPaperService researchPapers) {
        this.researchPapers = researchPapers;
    }

    // the researcher's own paper for the project this tracked paper is in
    @GetMapping("/internal/papers/{paperId}/research-paper")
    public ResponseEntity<byte[]> researchPaper(@PathVariable UUID paperId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(researchPapers.pdfForPaper(paperId));
    }
}
