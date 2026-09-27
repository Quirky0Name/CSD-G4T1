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
import java.util.UUID;

// One per paper per nudge that stored new alerts. Its alerts point at it (alerts.report_id) and so
// do its documents (report_documents.report_id). Owner and project come from the paper.
@Entity
@Table(name = "reports")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID paperId;

    @Column(nullable = false)
    @Convert(converter = ReportStatus.Converter.class)
    private ReportStatus status = ReportStatus.INVESTIGATING;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant investigatedAt;

    // written by impact (a later plan); nothing sets them yet
    private String evaluation;
    private String recommendation;
    private Instant evaluatedAt;

    protected Report() {
    }

    public Report(UUID paperId) {
        this.paperId = paperId;
        // Postgres keeps microseconds; truncating makes the first response match later reads
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    void markInvestigated() {
        status = ReportStatus.INVESTIGATED;
        investigatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public UUID getPaperId() {
        return paperId;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getInvestigatedAt() {
        return investigatedAt;
    }

    public String getEvaluation() {
        return evaluation;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
