package com.g4t1.storage.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ownership comes from papers.owner_id, so there's no owner column here
    @Column(nullable = false)
    private UUID paperId;

    @Column(nullable = false)
    @Convert(converter = ChangeType.Converter.class)
    private ChangeType changeType;

    @Column(nullable = false)
    private String changeKey;

    @Column(nullable = false)
    @Convert(converter = Severity.Converter.class)
    private Severity severity;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String recommendation;

    private String noticeDoi;

    @Column(nullable = false)
    private Instant detectedAt;

    @Column(nullable = false)
    private Long snapshotId;

    @Column(nullable = false)
    private Long previousSnapshotId;

    @Column(nullable = false)
    @Convert(converter = AlertStatus.Converter.class)
    private AlertStatus status = AlertStatus.NEW;

    private Instant statusChangedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected Alert() {
    }

    public Alert(UUID paperId, NewAlertRequest request) {
        this.paperId = paperId;
        this.changeType = request.changeType();
        this.changeKey = request.changeKey();
        this.severity = request.severity();
        this.description = request.description();
        this.recommendation = request.recommendation();
        this.noticeDoi = request.noticeDoi();
        this.detectedAt = request.detectedAt();
        this.snapshotId = request.snapshotId();
        this.previousSnapshotId = request.previousSnapshotId();
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getPaperId() {
        return paperId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getChangeKey() {
        return changeKey;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public String getNoticeDoi() {
        return noticeDoi;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public Long getPreviousSnapshotId() {
        return previousSnapshotId;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(Instant statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
