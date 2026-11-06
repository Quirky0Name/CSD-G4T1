package com.g4t1.storage.admin;

import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.paper.PaperDeletion;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.paper.PaperService;
import com.g4t1.storage.report.FakeDocument;
import com.g4t1.storage.report.ReportService;
import com.g4t1.storage.report.ReportStatus;
import com.g4t1.storage.report.UserReportResponse;
import com.g4t1.storage.snapshot.SnapshotResponse;
import com.g4t1.storage.snapshot.SnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

// What the admin can see of any researcher's papers, and clearing a paper's history
@Service
public class AdminService {

    private final PaperService paperService;
    private final PaperRepository papers;
    private final SnapshotService snapshots;
    private final PaperDeletion deletion;
    private final ReportService reports;
    private final FakeReportExample example;

    public AdminService(PaperService paperService, PaperRepository papers, SnapshotService snapshots,
                        PaperDeletion deletion, ReportService reports, FakeReportExample example) {
        this.paperService = paperService;
        this.papers = papers;
        this.snapshots = snapshots;
        this.deletion = deletion;
        this.reports = reports;
        this.example = example;
    }

    public List<AdminPaperResponse> papersOf(UUID ownerId) {
        return paperService.listForOwner(ownerId).stream()
                .map(paper -> new AdminPaperResponse(paper, ownerId,
                        snapshots.history(paper.id(), null, 1, null).stream().findFirst().orElse(null)))
                .toList();
    }

    public List<SnapshotResponse> snapshotsOf(UUID paperId) {
        return snapshots.history(paperId, null, null, null);
    }

    // Research Evaluation never raises a change key twice, so raising the same change again needs its alert gone
    public void clearHistory(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
        deletion.clearHistory(paperId);
    }

    // A copy of the example on any paper; it needs no DOI or snapshot. Its text names the example's paper
    // whichever paper it goes on, and its alerts point at no snapshot, hence the ids of 0.
    public UserReportResponse createFakeReport(UUID paperId, String status) {
        ReportStatus wanted = fakeReportStatus(status);
        FakeReportExample.Content content = example.content();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<NewAlertRequest> alerts = content.alerts().stream()
                .map(alert -> new NewAlertRequest(alert.changeType(), "fake:" + UUID.randomUUID(),
                        alert.alertSeverity(), alert.description(), alert.recommendation(), alert.noticeDoi(),
                        now, 0L, 0L))
                .toList();
        List<FakeDocument> documents = content.documents().stream().map(this::fakeDocument).toList();
        return reports.storeFake(paperId, alerts, documents, wanted, content.evaluation());
    }

    // the report UI links a PDF only through pdf_source_url, so a public route is what makes the link open
    private FakeDocument fakeDocument(FakeReportExample.ExampleDocument document) {
        String file = document.pdfFile();
        return new FakeDocument(document.kind(), document.doi(), document.crossrefStatus(), document.crossrefRecord(),
                document.updateToIncludesPaper(), document.textStatus(), document.text(), document.textTruncated(),
                document.links(), file == null ? null : example.servedPdf(file).orElseThrow(),
                file == null ? null : "/api/public/fake-report/" + file);
    }

    private static ReportStatus fakeReportStatus(String status) {
        if (status == null) {
            return ReportStatus.ASSESSED;
        }
        try {
            return ReportStatus.of(status);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be investigating, investigated or assessed");
        }
    }
}
