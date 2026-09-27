package com.g4t1.storage.report;

import com.g4t1.storage.alert.AlertRepository;
import com.g4t1.storage.paper.PaperRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

@Service
public class ReportService {

    private final ReportRepository reports;
    private final AlertRepository alerts;
    private final PaperRepository papers;
    private final ReportDocumentService documents;
    private final JsonMapper json;

    public ReportService(ReportRepository reports, AlertRepository alerts, PaperRepository papers,
                         ReportDocumentService documents, JsonMapper json) {
        this.reports = reports;
        this.alerts = alerts;
        this.papers = papers;
        this.documents = documents;
        this.json = json;
    }

    /**
     * Opens a report grouping every alert of the paper that isn't in a report yet: in practice the
     * ones the latest nudge stored, plus any a crash left ungrouped. Empty when there are none,
     * including when another open took them between the check and the update.
     */
    @Transactional
    public Optional<ReportResponse> open(UUID paperId) {
        requirePaper(paperId);
        if (!alerts.existsByPaperIdAndReportIdIsNull(paperId)) {
            return Optional.empty();
        }
        Report report = reports.saveAndFlush(new Report(paperId));
        if (alerts.assignUnreportedToReport(paperId, report.getId()) == 0) {
            // lost the race: the other open has them, so this report would be empty
            reports.delete(report);
            return Optional.empty();
        }
        return Optional.of(toResponse(report));
    }

    /**
     * The report with its alerts and documents. A report of another paper is the same 404 as a
     * missing one.
     */
    @Transactional(readOnly = true)
    public ReportResponse get(UUID paperId, long reportId) {
        return toResponse(requireReport(paperId, reportId));
    }

    /**
     * Investigation marks a report done. Only "investigated" can be set here; impact will set
     * "assessed" through its own endpoint. Marking it again changes nothing, so investigated_at keeps
     * the first time.
     */
    @Transactional
    public ReportResponse changeStatus(UUID paperId, long reportId, ReportStatus status) {
        if (status != ReportStatus.INVESTIGATED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be investigated");
        }
        Report report = requireReport(paperId, reportId);
        if (report.getStatus() == ReportStatus.ASSESSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report " + reportId + " is already assessed");
        }
        if (report.getStatus() == ReportStatus.INVESTIGATING) {
            report.markInvestigated();
        }
        return toResponse(report);
    }

    private Report requireReport(UUID paperId, long reportId) {
        requirePaper(paperId);
        return reports.findById(reportId)
                .filter(report -> report.getPaperId().equals(paperId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No report " + reportId));
    }

    // the same "No paper" 404 as the other internal endpoints, which Research Evaluation reads as the paper being gone
    private void requirePaper(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
    }

    private ReportResponse toResponse(Report report) {
        return new ReportResponse(report.getId(), report.getPaperId(), report.getStatus(), report.getCreatedAt(),
                report.getInvestigatedAt(), report.getEvaluation(), report.getRecommendation(),
                report.getEvaluatedAt(), report.getChangeSummary(), report.getChangeSeverity(),
                report.getImpactLevel(), report.getAssessment() == null ? null : json.readTree(report.getAssessment()),
                alerts.findByReportIdOrderByIdAsc(report.getId()).stream().map(ReportAlertResponse::from).toList(),
                documents.forReport(report.getId()));
    }
}
