package com.g4t1.storage.paper;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// service-token only; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
@RequestMapping("/internal/papers")
public class InternalPaperController {

    private final PaperRepository papers;
    private final PaperService paperService;

    public InternalPaperController(PaperRepository papers, PaperService paperService) {
        this.papers = papers;
        this.paperService = paperService;
    }

    // every user's papers, for Updating's poll job to sync
    @GetMapping
    public List<InternalPaperResponse> list() {
        return papers.findAll().stream().map(InternalPaperResponse::from).toList();
    }

    // the paper itself, for Research Evaluation; never read from the upload folder directly
    @GetMapping("/{paperId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID paperId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(paperService.storedPdf(paperId));
    }
}
