package com.g4t1.storage.report;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// a report document's stored PDF for the researcher, so the frontend can show a report's highlights
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final ReportDocumentService documents;

    public DocumentController(ReportDocumentService documents) {
        this.documents = documents;
    }

    // only the owner of the report's paper; anyone else's, a missing one and a notice are the same 404
    @GetMapping("/{documentId}/pdf")
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal UUID userId, @PathVariable long documentId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(documents.ownedPdf(userId, documentId));
    }
}
