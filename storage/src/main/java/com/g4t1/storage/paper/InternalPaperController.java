package com.g4t1.storage.paper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// service-token only; SecurityConfig limits /internal/** to ROLE_SERVICE
@RestController
@RequestMapping("/internal/papers")
public class InternalPaperController {

    private final PaperRepository papers;

    public InternalPaperController(PaperRepository papers) {
        this.papers = papers;
    }

    // every user's papers, for Updating's poll job to sync
    @GetMapping
    public List<InternalPaperResponse> list() {
        return papers.findAll().stream().map(InternalPaperResponse::from).toList();
    }
}
