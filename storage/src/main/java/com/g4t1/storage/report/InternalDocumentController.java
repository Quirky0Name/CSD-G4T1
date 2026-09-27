package com.g4t1.storage.report;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Research Evaluation stores what investigation fetched here; service-token only. Flat, since the
// report_id in the body already names the report (and through it the paper)
@RestController
@RequestMapping("/internal/documents")
public class InternalDocumentController {

    private final ReportDocumentService documents;

    public InternalDocumentController(ReportDocumentService documents) {
        this.documents = documents;
    }

    // 201 with the stored row, or 200 with the row already stored for that DOI in the report
    @PostMapping
    public ResponseEntity<ReportDocumentResponse> store(@Valid @RequestBody NewReportDocumentRequest request) {
        ReportDocumentService.StoredDocument stored = documents.store(request);
        return ResponseEntity.status(stored.created() ? HttpStatus.CREATED : HttpStatus.OK).body(stored.document());
    }
}
