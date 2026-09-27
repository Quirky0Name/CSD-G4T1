package com.g4t1.storage.report;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// What investigation fetched for one DOI of a report: its Crossref record, its open-access text and,
// for a new version or the current copy, its PDF. Never overwritten once stored.
@Entity
@Table(name = "report_documents")
public class ReportDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false)
    @Convert(converter = DocumentKind.Converter.class)
    private DocumentKind kind;

    @Column(nullable = false)
    private String doi;

    @Column(nullable = false)
    @Convert(converter = CrossrefStatus.Converter.class)
    private CrossrefStatus crossrefStatus;

    // raw JSON text
    private String crossrefRecord;

    private Boolean updateToIncludesPaper;

    @Column(nullable = false)
    @Convert(converter = TextStatus.Converter.class)
    private TextStatus textStatus;

    private String text;

    @Column(nullable = false)
    private boolean textTruncated;

    @Column(nullable = false)
    @Convert(converter = PdfStatus.Converter.class)
    private PdfStatus pdfStatus;

    private String fileKey;
    private String sha256;
    private String pdfSourceUrl;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant pdfFetchedAt;

    protected ReportDocument() {
    }

    ReportDocument(NewReportDocumentRequest request, String crossrefRecord) {
        this.reportId = request.reportId();
        this.kind = request.kind();
        this.doi = request.doi();
        this.crossrefStatus = request.crossrefStatus();
        this.crossrefRecord = crossrefRecord;
        this.updateToIncludesPaper = request.updateToIncludesPaper();
        this.textStatus = request.textStatus();
        this.text = request.text();
        this.textTruncated = request.textTruncated();
        // a notice never gets a PDF; the others wait for Storage Management's download
        this.pdfStatus = kind.hasPdf() ? PdfStatus.PENDING : PdfStatus.SKIPPED;
        // Postgres keeps microseconds; truncating makes the first response match later reads
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getReportId() {
        return reportId;
    }

    public DocumentKind getKind() {
        return kind;
    }

    public String getDoi() {
        return doi;
    }

    public CrossrefStatus getCrossrefStatus() {
        return crossrefStatus;
    }

    public String getCrossrefRecord() {
        return crossrefRecord;
    }

    public Boolean getUpdateToIncludesPaper() {
        return updateToIncludesPaper;
    }

    public TextStatus getTextStatus() {
        return textStatus;
    }

    public String getText() {
        return text;
    }

    public boolean isTextTruncated() {
        return textTruncated;
    }

    public PdfStatus getPdfStatus() {
        return pdfStatus;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getSha256() {
        return sha256;
    }

    public String getPdfSourceUrl() {
        return pdfSourceUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPdfFetchedAt() {
        return pdfFetchedAt;
    }
}
