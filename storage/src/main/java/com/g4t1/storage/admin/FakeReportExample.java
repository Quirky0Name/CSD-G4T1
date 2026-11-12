package com.g4t1.storage.admin;

import com.g4t1.storage.alert.AlertSeverity;
import com.g4t1.storage.alert.ChangeType;
import com.g4t1.storage.report.CrossrefStatus;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.g4t1.storage.report.DocumentKind;
import com.g4t1.storage.report.FeedbackVerdict;
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
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

// The one saved example every fake report and fake paper copies (docs/STORAGE.md, "Dev mode"). It only reads
// the files: nothing here generates anything. paper.pdf is what a fake paper stores, and draft.pdf the draft
// the example's claims quote; the frontend reads all the PDFs through the public route. past.json is the
// invented past an assessed fake report is stored with: an earlier report on the same paper, its feedback, a
// made-up profile and the fake report's context items, which name that past by placeholder.
@Component
public class FakeReportExample {

    private static final String FOLDER = "dev/fake-report/";
    // made up, and all a link opened in a new tab can get, since it can't send a token
    private static final Set<String> SERVED_PDFS =
            Set.of("new-version.pdf", "current-version.pdf", "paper.pdf", "draft.pdf",
                    "past-new-version.pdf", "past-related.pdf");

    // the details printed on paper.pdf's first page, which a fake paper is stored with
    public static final String PAPER_TITLE = "Dose-response of iron supplementation on fatigue";
    public static final String PAPER_JOURNAL = "J. Fake Nutrition";
    public static final int PAPER_YEAR = 2019;

    private final JsonMapper json;

    public FakeReportExample(JsonMapper json) {
        this.json = json;
    }

    public Content content() {
        return read("report.json", Content.class);
    }

    public PastContent pastContent() {
        return read("past.json", PastContent.class);
    }

    private <T> T read(String file, Class<T> type) {
        try (InputStream in = new ClassPathResource(FOLDER + file).getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the fake report example's " + file, e);
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

    public record PastContent(List<ExampleAlert> alerts, List<ExampleDocument> documents,
                              ReportEvaluationRequest evaluation, ExampleFeedback feedback, ExampleProfile profile,
                              List<PastItem> contextChunks) {
    }

    public record ExampleFeedback(FeedbackVerdict verdict, String note) {
    }

    // in GET /auth/me/profile's shape; never anyone's real profile
    public record ExampleProfile(String occupation, String occupationOther, List<String> researchFields,
                                 List<String> researchInterests, List<String> workTypes,
                                 String responsePreferences) {
    }

    // One context item naming the past by placeholder: index is the alert's or document's position in
    // past.json (none for the report), swapped for the stored row's id when the past is stored
    public record PastItem(PastSource source, Integer index, Integer page, Integer start, Integer end) {
    }

    public enum PastSource {
        ALERT, DOCUMENT, REPORT;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static PastSource of(String value) {
            for (PastSource source : values()) {
                if (source.value().equals(value)) {
                    return source;
                }
            }
            throw new IllegalArgumentException("Unknown source " + value);
        }
    }
}
