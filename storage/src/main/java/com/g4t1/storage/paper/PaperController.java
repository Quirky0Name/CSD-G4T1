package com.g4t1.storage.paper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/papers")
public class PaperController {

    private final PaperService papers;

    public PaperController(PaperService papers) {
        this.papers = papers;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PaperResponse upload(@AuthenticationPrincipal UUID userId,
                                @RequestParam("file") MultipartFile file) throws IOException {
        return papers.uploadPdf(userId, file);
    }
}
