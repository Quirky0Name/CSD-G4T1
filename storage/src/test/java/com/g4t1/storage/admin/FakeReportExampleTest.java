package com.g4t1.storage.admin;

import com.g4t1.storage.report.DocumentKind;
import com.g4t1.storage.report.FeedbackVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// The invented past saved beside report.json: that it parses, that its context items name what it holds,
// and that its profile keeps the profile rules (docs/CONTRACTS.md, "PUT /auth/me/profile")
class FakeReportExampleTest {

    // users/src/main/resources/profile/options.json, the lists the profile rules accept
    private static final Set<String> OCCUPATIONS = Set.of("Undergraduate student", "Master's student",
            "PhD student", "Postdoctoral researcher", "Faculty or principal investigator", "Research staff",
            "Clinician", "Industry researcher", "Other");
    private static final Set<String> FIELDS = Set.of("Agricultural and Biological Sciences",
            "Arts and Humanities", "Biochemistry, Genetics and Molecular Biology",
            "Business, Management and Accounting", "Chemical Engineering", "Chemistry", "Computer Science",
            "Decision Sciences", "Dentistry", "Earth and Planetary Sciences", "Economics, Econometrics and Finance",
            "Energy", "Engineering", "Environmental Science", "Health Professions", "Immunology and Microbiology",
            "Materials Science", "Mathematics", "Medicine", "Neuroscience", "Nursing",
            "Pharmacology, Toxicology and Pharmaceutics", "Physics and Astronomy", "Psychology", "Social Sciences",
            "Veterinary");
    private static final Set<String> WORK_TYPES = Set.of("Experimental or laboratory", "Clinical",
            "Computational or modelling", "Theoretical", "Survey or qualitative",
            "Systematic review or meta-analysis", "Data or software", "Other");

    private final FakeReportExample example = new FakeReportExample(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());

    @Test
    void thePastIsOneEarlierReportWithADocumentOfEachKindAndOverstatedFeedback() {
        FakeReportExample.PastContent past = example.pastContent();

        assertThat(past.alerts()).isNotEmpty();
        assertThat(past.documents()).extracting(FakeReportExample.ExampleDocument::kind)
                .containsExactlyInAnyOrder(DocumentKind.NOTICE, DocumentKind.NEW_VERSION,
                        DocumentKind.CURRENT_VERSION, DocumentKind.RELATED);
        assertThat(past.documents()).filteredOn(document -> document.kind() == DocumentKind.CURRENT_VERSION)
                .extracting(FakeReportExample.ExampleDocument::pdfFile).containsExactly("paper.pdf");
        assertThat(past.evaluation().assessment().get("fake").asBoolean()).isTrue();
        assertThat(past.feedback().verdict()).isEqualTo(FeedbackVerdict.OVERSTATED);
        assertThat(past.feedback().note()).isNotBlank().hasSizeLessThanOrEqualTo(500);
    }

    @Test
    void everyContextItemNamesAPastAlertDocumentOrThePastReport() {
        FakeReportExample.PastContent past = example.pastContent();

        assertThat(past.contextChunks()).extracting(FakeReportExample.PastItem::source)
                .contains(FakeReportExample.PastSource.ALERT, FakeReportExample.PastSource.DOCUMENT,
                        FakeReportExample.PastSource.REPORT);
        for (FakeReportExample.PastItem item : past.contextChunks()) {
            switch (item.source()) {
                case ALERT -> assertThat(item.index()).isBetween(0, past.alerts().size() - 1);
                case DOCUMENT -> assertThat(item.index()).isBetween(0, past.documents().size() - 1);
                case REPORT -> assertThat(item.index()).isNull();
            }
            if (item.source() != FakeReportExample.PastSource.DOCUMENT) {
                assertThat(item.page()).isNull();
                assertThat(item.start()).isNull();
                assertThat(item.end()).isNull();
            }
        }
    }

    @Test
    void everyPageIsOneItsPdfHasAndEveryRangeLiesInsideTheNoticeText() throws IOException {
        FakeReportExample.PastContent past = example.pastContent();
        boolean ranged = false;

        for (FakeReportExample.PastItem item : past.contextChunks()) {
            if (item.source() != FakeReportExample.PastSource.DOCUMENT) {
                continue;
            }
            FakeReportExample.ExampleDocument document = past.documents().get(item.index());
            if (item.page() != null) {
                assertThat(document.pdfFile()).isNotNull();
                assertThat(example.servedPdf(document.pdfFile())).isPresent();
                assertThat(item.page()).isBetween(1, pageCount(document.pdfFile()));
                assertThat(item.start()).isNull();
                assertThat(item.end()).isNull();
            } else {
                assertThat(document.kind()).isEqualTo(DocumentKind.NOTICE);
                assertThat(item.start()).isNotNull();
                assertThat(item.end()).isNotNull();
                assertThat(item.start()).isGreaterThanOrEqualTo(0).isLessThan(item.end());
                assertThat(item.end()).isLessThanOrEqualTo(document.text().length());
                ranged = true;
            }
        }
        assertThat(ranged).isTrue();
    }

    @Test
    void theProfileKeepsTheProfileRules() {
        FakeReportExample.ExampleProfile profile = example.pastContent().profile();

        assertThat(OCCUPATIONS).contains(profile.occupation());
        if (!"Other".equals(profile.occupation())) {
            assertThat(profile.occupationOther()).isNull();
        }
        assertThat(profile.researchFields()).isNotEmpty().doesNotHaveDuplicates().allMatch(FIELDS::contains);
        assertThat(profile.workTypes()).doesNotHaveDuplicates().allMatch(WORK_TYPES::contains);
        assertThat(profile.researchInterests()).isNotEmpty().hasSizeLessThanOrEqualTo(5)
                .allSatisfy(interest -> assertThat(interest).isEqualTo(interest.strip())
                        .hasSizeBetween(1, 60).doesNotContainPattern("\\p{Cntrl}"));
        Set<String> lowered = new HashSet<>();
        profile.researchInterests().forEach(interest -> assertThat(lowered.add(interest.toLowerCase(Locale.ROOT)))
                .as("duplicate interest %s", interest).isTrue());
        assertThat(profile.responsePreferences()).isNotBlank().hasSizeLessThanOrEqualTo(500)
                .isEqualTo(profile.responsePreferences().strip());
    }

    @Test
    void thePastPdfsAreServedAndPastJsonIsNot() {
        assertThat(example.servedPdf("past-new-version.pdf")).isPresent();
        assertThat(example.servedPdf("past-related.pdf")).isPresent();
        assertThat(example.servedPdf("past.json")).isEmpty();
    }

    // the example PDFs keep their page tree uncompressed, so the root's /Count is the page count
    private static int pageCount(String file) throws IOException {
        String raw = new String(new ClassPathResource("dev/fake-report/" + file).getContentAsByteArray(),
                StandardCharsets.ISO_8859_1);
        Matcher count = Pattern.compile("/Count (\\d+)").matcher(raw);
        int pages = 0;
        while (count.find()) {
            pages = Math.max(pages, Integer.parseInt(count.group(1)));
        }
        assertThat(pages).as("page count of %s", file).isPositive();
        return pages;
    }
}
