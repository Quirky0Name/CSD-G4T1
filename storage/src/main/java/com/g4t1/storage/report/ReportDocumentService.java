package com.g4t1.storage.report;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Service
public class ReportDocumentService {

    private final ReportDocumentRepository documents;
    private final ReportRepository reports;
    private final JsonMapper json;

    public ReportDocumentService(ReportDocumentRepository documents, ReportRepository reports, JsonMapper json) {
        this.documents = documents;
        this.reports = reports;
        this.json = json;
    }

    /**
     * Stores what investigation fetched for one DOI of a report. A DOI already stored for the report
     * returns the stored row untouched, whatever the new body says: Research Evaluation uses the
     * returned row, and whether a difference matters is for a later sprint. If two requests for the
     * same DOI come at once, the unique constraint keeps the first and the second gets it back.
     */
    public StoredDocument store(NewReportDocumentRequest request) {
        if (!reports.existsById(request.reportId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No report " + request.reportId());
        }
        var existing = documents.findByReportIdAndDoi(request.reportId(), request.doi());
        if (existing.isPresent()) {
            return new StoredDocument(toResponse(existing.get()), false);
        }
        try {
            ReportDocument saved = documents.saveAndFlush(
                    new ReportDocument(request, toText(request.crossrefRecord())));
            return new StoredDocument(toResponse(saved), true);
        } catch (DataIntegrityViolationException e) {
            // lost the race; anything else (e.g. the report was deleted meanwhile) is a real error
            return documents.findByReportIdAndDoi(request.reportId(), request.doi())
                    .map(winner -> new StoredDocument(toResponse(winner), false))
                    .orElseThrow(() -> e);
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
