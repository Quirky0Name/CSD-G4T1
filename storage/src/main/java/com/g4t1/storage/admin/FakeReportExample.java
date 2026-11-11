package com.g4t1.storage.admin;

import com.g4t1.storage.alert.AlertSeverity;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.report.CrossrefStatus;
import com.g4t1.storage.report.DocumentKind;
import com.g4t1.storage.report.ReportEvaluationRequest;
import com.g4t1.storage.report.TextStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// The one saved example every fake report and fake paper copies (docs/STORAGE.md, "Dev mode"). It only reads
// the files: nothing here generates anything. paper.pdf is what a fake paper stores, and draft.pdf the draft
// the example's claims quote; the frontend reads all four PDFs through the public route.
@Component
public class FakeReportExample {

    private static final String FOLDER = "dev/fake-report/";
    // made up, and all a link opened in a new tab can get, since it can't send a token
    private static final Set<String> SERVED_PDFS =
            Set.of("new-version.pdf", "current-version.pdf", "paper.pdf", "draft.pdf");

    // the details printed on paper.pdf's first page, which a fake paper is stored with
    public static final String PAPER_TITLE = "Dose-response of iron supplementation on fatigue";
    public static final String PAPER_JOURNAL = "J. Fake Nutrition";
    public static final int PAPER_YEAR = 2019;

    private final JsonMapper json;

    public FakeReportExample(JsonMapper json) {
        this.json = json;
    }

    public Content content() {
        try (InputStream in = new ClassPathResource(FOLDER + "report.json").getInputStream()) {
            return json.readValue(in, Content.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the fake report example", e);
        }
    }

    // empty for any name but the served ones, so no other resource can be read through this
    public Optional<byte[]> servedPdf(String file) {
        if (!SERVED_PDFS.contains(file)) {
            return Optional.empty();
        }
        try (InputStream in = new ClassPathResource(FOLDER + file).getInputStream()) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    public record Content(List<ExampleAlert> alerts, List<ExampleDocument> documents,
                          ReportEvaluationRequest evaluation) {
    }

    public record ExampleAlert(ChangeType changeType, AlertSeverity alertSeverity, String description,
                               String recommendation, String noticeDoi) {
    }

    public record ExampleDocument(DocumentKind kind, String doi, CrossrefStatus crossrefStatus,
                                  JsonNode crossrefRecord, Boolean updateToIncludesPaper, TextStatus textStatus,
                                  String text, boolean textTruncated, JsonNode links, String pdfFile) {
    }
}
