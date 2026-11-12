package com.g4t1.storage.admin;

import com.g4t1.storage.alert.NewAlertRequest;
import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperDeletion;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.paper.PaperResponse;
import com.g4t1.storage.paper.PaperService;
import com.g4t1.storage.report.FakeDocument;
import com.g4t1.storage.report.ReportEvaluationRequest;
import com.g4t1.storage.report.ReportService;
import com.g4t1.storage.report.ReportStatus;
import com.g4t1.storage.report.UserReportResponse;
import com.g4t1.storage.snapshot.SnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

// What the admin can see of any researcher's papers, clearing a paper's history, and fake papers and reports
@Service
public class AdminService {

    // how long before an assessed fake report its invented past is dated
    private static final Duration PAST_AGE = Duration.ofDays(30);

    private final PaperService paperService;
    private final PaperRepository papers;
    private final SnapshotService snapshots;
    private final PaperDeletion deletion;
    private final ReportService reports;
    private final FakeReportExample example;
    private final LocalFileStore files;
    private final JsonMapper json;

    public AdminService(PaperService paperService, PaperRepository papers, SnapshotService snapshots,
                        PaperDeletion deletion, ReportService reports, FakeReportExample example,
                        LocalFileStore files, JsonMapper json) {
        this.paperService = paperService;
        this.papers = papers;
        this.snapshots = snapshots;
        this.deletion = deletion;
        this.reports = reports;
        this.example = example;
        this.files = files;
        this.json = json;
    }

    // The example's paper, as a made-up upload: no GROBID or metadata lookup, and no DOI, so Updating's poll
    // skips it rather than looking up the made-up one its PDF prints. The researcher's draft is not touched.
    public AdminPaperResponse createFakePaper(UUID ownerId) {
        Paper paper = new Paper(ownerId);
        paper.setTitle(FakeReportExample.PAPER_TITLE);
        paper.setJournal(FakeReportExample.PAPER_JOURNAL);
        paper.setPublicationYear(FakeReportExample.PAPER_YEAR);
        String key = files.save(example.servedPdf("paper.pdf").orElseThrow());
        paper.setFileKey(key);
        Paper saved;
        try {
            saved = papers.save(paper);
        } catch (RuntimeException e) {
            files.delete(key); // no row points at the file, so nothing else would ever delete it
            throw e;
        }
        return new AdminPaperResponse(new PaperResponse(saved.getId(), saved.getProjectId(), saved.getDoi(),
                saved.getOpenalexId(), saved.getTitle(), saved.getJournal(), saved.getIssn(),
                saved.getPublicationYear(), saved.hasFile(), saved.getCreatedAt()), ownerId, null);
    }

    public List<AdminPaperResponse> papersOf(UUID ownerId) {
        return paperService.listForOwner(ownerId).stream()
                .map(paper -> new AdminPaperResponse(paper, ownerId,
                        snapshots.history(paper.id(), null, 1, null).stream().findFirst().orElse(null)))
                .toList();
    }

    // Research Evaluation never raises a change key twice, so raising the same change again needs its alert gone
    public void clearHistory(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
        deletion.clearHistory(paperId);
    }

    // A copy of the example on any paper; it needs no DOI or snapshot. Its text names the example's paper
    // whichever paper it goes on, and its alerts point at no snapshot, hence the ids of 0. An assessed one
    // comes with the example's invented past, stored first and dated 30 days earlier, which its context
    // items name; both or neither are stored.
    @Transactional
    public UserReportResponse createFakeReport(UUID paperId, String status) {
        ReportStatus wanted = fakeReportStatus(status);
        FakeReportExample.Content content = example.content();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ReportEvaluationRequest evaluation = content.evaluation();
        if (wanted == ReportStatus.ASSESSED) {
            evaluation = evaluation.withContextChunks(storePast(paperId, now.minus(PAST_AGE)));
        }
        return reports.storeFake(paperId, fakeAlerts(content.alerts(), now), fakeDocuments(content.documents()),
                wanted, evaluation);
    }

    private ArrayNode storePast(UUID paperId, Instant at) {
        FakeReportExample.PastContent past = example.pastContent();
        ReportService.StoredFakePast stored = reports.storeFakePast(paperId, fakeAlerts(past.alerts(), at),
                fakeDocuments(past.documents()), past.evaluation(),
                past.feedback().verdict(), past.feedback().note(), at);
        ArrayNode items = json.createArrayNode();
        for (FakeReportExample.PastItem item : past.contextChunks()) {
            long id = switch (item.source()) {
                case ALERT -> stored.alertIds().get(item.index());
                case DOCUMENT -> stored.documentIds().get(item.index());
                case REPORT -> stored.reportId();
            };
            ObjectNode node = items.addObject()
                    .put("source_type", item.source().value())
                    .put("source_id", String.valueOf(id))
                    .put("page", item.page());
            if (item.start() != null) {
                node.put("start", item.start()).put("end", item.end());
            }
        }
        // what a real feedback item carries (docs/CONTRACTS.md, GET /papers/{id}/reports)
        items.addObject()
                .put("source_type", "feedback")
                .put("source_id", String.valueOf(stored.reportId()))
                .putNull("page")
                .put("paper_id", paperId.toString())
                .put("paper_title", papers.findById(paperId).map(Paper::getTitle).orElse(null))
                .put("source_date", stored.feedbackUpdatedAt().toString())
                .put("verdict", past.feedback().verdict().value());
        // the only item carrying its content, since no row holds the invented profile
        items.addObject()
                .put("source_type", "profile")
                .put("source_id", "example")
                .putNull("page")
                .set("profile", json.valueToTree(past.profile()));
        return items;
    }

    private List<FakeDocument> fakeDocuments(List<FakeReportExample.ExampleDocument> documents) {
        return documents.stream().map(this::fakeDocument).toList();
    }

    private static List<NewAlertRequest> fakeAlerts(List<FakeReportExample.ExampleAlert> alerts, Instant at) {
        return alerts.stream()
                .map(alert -> new NewAlertRequest(alert.changeType(), "fake:" + UUID.randomUUID(),
                        alert.alertSeverity(), alert.description(), alert.recommendation(), alert.noticeDoi(),
                        at, 0L, 0L))
                .toList();
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
