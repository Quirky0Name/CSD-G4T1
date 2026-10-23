package com.g4t1.storage.draft;

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

// the researcher's draft, one per project; no project_id (or "") means the default project
@RestController
@RequestMapping("/draft")
public class DraftController {

    private final DraftService drafts;

    public DraftController(DraftService drafts) {
        this.drafts = drafts;
    }

    // 201 for the project's first draft, 200 when it replaced one
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DraftResponse> upload(@AuthenticationPrincipal UUID userId,
                                                @RequestParam("file") MultipartFile file,
                                                @RequestParam(name = "project_id", required = false) UUID projectId)
            throws IOException {
        var stored = drafts.upload(userId, projectId, file);
        return ResponseEntity.status(stored.created() ? HttpStatus.CREATED : HttpStatus.OK).body(stored.draft());
    }

    @GetMapping
    public DraftResponse get(@AuthenticationPrincipal UUID userId,
                             @RequestParam(name = "project_id", required = false) UUID projectId) {
        return drafts.find(userId, projectId);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UUID userId,
                       @RequestParam(name = "project_id", required = false) UUID projectId) {
        drafts.delete(userId, projectId);
    }
}
