package com.g4t1.storage.research;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

// the researcher's own paper, one per project; no folder_id (or "") means the "no folder" project
@RestController
@RequestMapping("/research-paper")
public class ResearchPaperController {

    private final ResearchPaperService researchPapers;

    public ResearchPaperController(ResearchPaperService researchPapers) {
        this.researchPapers = researchPapers;
    }

    // 201 for the project's first research paper, 200 when it replaced one
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResearchPaperResponse> upload(@AuthenticationPrincipal UUID userId,
                                                        @RequestParam("file") MultipartFile file,
                                                        @RequestParam(name = "folder_id", required = false) UUID folderId)
            throws IOException {
        var stored = researchPapers.upload(userId, folderId, file);
        return ResponseEntity.status(stored.created() ? HttpStatus.CREATED : HttpStatus.OK).body(stored.researchPaper());
    }

    @GetMapping
    public ResearchPaperResponse get(@AuthenticationPrincipal UUID userId,
                                     @RequestParam(name = "folder_id", required = false) UUID folderId) {
        return researchPapers.find(userId, folderId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UUID userId,
                       @RequestParam(name = "folder_id", required = false) UUID folderId) {
        researchPapers.delete(userId, folderId);
    }
}
