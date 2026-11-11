package com.g4t1.storage.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Dev mode only. No token (SecurityConfig), because a PDF link opened in a new tab can't send one
@RestController
@ConditionalOnBooleanProperty("storage.dev-mode")
public class FakeReportPdfController {

    private final FakeReportExample example;

    public FakeReportPdfController(FakeReportExample example) {
        this.example = example;
    }

    @GetMapping("/public/fake-report/{file}")
    public ResponseEntity<byte[]> pdf(@PathVariable String file) {
        byte[] pdf = example.servedPdf(file)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No file " + file));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf);
    }
}
