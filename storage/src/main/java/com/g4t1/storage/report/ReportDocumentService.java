package com.g4t1.storage.report;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class ReportDocumentService {

    private static final Logger log = LoggerFactory.getLogger(ReportDocumentService.class);

    private final ReportDocumentRepository documents;
    private final ReportRepository reports;
    private final OpenAccessPdfClient openAccess;
    private final LocalFileStore files;
    private final JsonMapper json;

    public ReportDocumentService(ReportDocumentRepository documents, ReportRepository reports,
                                 OpenAccessPdfClient openAccess, LocalFileStore files, JsonMapper json) {
        this.documents = documents;
        this.reports = reports;
        this.openAccess = openAccess;
        this.files = files;
        this.json = json;
    }

    /**
     * Stores what investigation fetched for one DOI of a report and, for a new version or the
     * current copy, downloads its open-access PDF. A DOI already stored for the report returns the
     * stored row untouched, whatever the new body says and whatever its PDF status: nothing is
     * downloaded again. Research Evaluation uses the returned row, and whether a difference matters is
     * for a later sprint. If two requests for the same DOI come at once, the unique constraint keeps
     * the first (which alone downloads) and the second gets it back.
     */
    public StoredDocument store(NewReportDocumentRequest request) {
        if (!reports.existsById(request.reportId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No report " + request.reportId());
        }
        var existing = documents.findByReportIdAndDoi(request.reportId(), request.doi());
        if (existing.isPresent()) {
            return new StoredDocument(toResponse(existing.get()), false);
        }
        ReportDocument saved;
        try {
            saved = documents.saveAndFlush(new ReportDocument(request, toText(request.crossrefRecord())));
        } catch (DataIntegrityViolationException e) {
            // lost the race; anything else (e.g. the report was deleted meanwhile) is a real error
            return documents.findByReportIdAndDoi(request.reportId(), request.doi())
                    .map(winner -> new StoredDocument(toResponse(winner), false))
                    .orElseThrow(() -> e);
        }
        if (saved.getPdfStatus() == PdfStatus.PENDING) {
            saved = downloadPdf(saved);
        }
        return new StoredDocument(toResponse(saved), true);
    }

    /**
     * The document's stored PDF, for impact later. It only reads, never downloads. An unknown
     * document, one with no stored PDF (a notice, pending or not found) and one whose file is missing
     * from disk each have their own 404 detail.
     */
    public byte[] storedPdf(long documentId) {
        ReportDocument document = documents.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No document " + documentId));
        if (document.getPdfStatus() != PdfStatus.OK || document.getFileKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + documentId + " has no stored PDF");
        }
        return files.read(document.getFileKey()).orElseThrow(() -> {
            log.warn("Document {} points at a PDF that isn't on disk", documentId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The PDF for document " + documentId + " is missing from disk");
        });
    }

    // the row is already saved as pending, so a slow download holds no transaction open; a crash
    // here leaves it pending, and it isn't tried again (an existing row is never downloaded)
    private ReportDocument downloadPdf(ReportDocument document) {
        var downloaded = openAccess.downloadWithSource(document.getDoi());
        if (downloaded.isEmpty()) {
            document.recordNoPdf();
            return documents.saveAndFlush(document);
        }
        byte[] bytes = downloaded.get().bytes();
        String key = files.save(bytes);
        try {
            document.recordPdf(key, sha256(bytes), downloaded.get().sourceUrl());
            return documents.saveAndFlush(document);
        } catch (RuntimeException e) {
            // no row points at the new file
            files.delete(key);
            throw e;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    // oldest first
    List<ReportDocumentResponse> forReport(long reportId) {
        return documents.findByReportIdOrderByIdAsc(reportId).stream().map(this::toResponse).toList();
    }

    private ReportDocumentResponse toResponse(ReportDocument d) {
        return new ReportDocumentResponse(d.getId(), d.getReportId(), d.getKind(), d.getDoi(),
                d.getCrossrefStatus(), toNode(d.getCrossrefRecord()), d.getUpdateToIncludesPaper(),
                d.getTextStatus(), d.getText(), d.isTextTruncated(), d.getPdfStatus(), d.getFileKey(),
                d.getSha256(), d.getPdfSourceUrl(), d.getCreatedAt(), d.getPdfFetchedAt());
    }

    // a JSON null is stored as SQL null, not the text "null"
    private String toText(JsonNode node) {
        return node == null || node.isNull() ? null : json.writeValueAsString(node);
    }

    private JsonNode toNode(String text) {
        return text == null ? null : json.readTree(text);
    }

    public record StoredDocument(ReportDocumentResponse document, boolean created) {
    }
}
