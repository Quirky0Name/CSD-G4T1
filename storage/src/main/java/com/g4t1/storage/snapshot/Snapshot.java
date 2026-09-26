package com.g4t1.storage.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Insert-only: nothing sets a field after the row is saved, so there are no setters.
@Entity
@Table(name = "background_metadata")
public class Snapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long snapshotId;

    @Column(nullable = false)
    private UUID paperId;

    @Column(nullable = false)
    private String doi;

    @Column(nullable = false)
    private Instant fetchedAt;

    private String openalexId;
    private String title;
    private Integer publicationYear;
    private Boolean isRetracted;
    private Boolean inDoaj;
    private String journalSourceId;
    private String journalSourceType;
    private String journal;

    @Column(name = "issn_l")
    private String issnL;
    private String publisher;
    private Integer citedByCount;

    // raw JSON text
    private String crossrefUpdates;
    private String authors;

    @Column(nullable = false)
    private String sourceStatus;

    protected Snapshot() {
    }

    Snapshot(UUID paperId, SnapshotRequest body, String crossrefUpdates, String authors, String sourceStatus) {
        this.paperId = paperId;
        this.doi = body.doi();
        this.fetchedAt = body.fetchedAt();
        this.openalexId = body.openalexId();
        this.title = body.title();
        this.publicationYear = body.publicationYear();
        this.isRetracted = body.isRetracted();
        this.inDoaj = body.inDoaj();
        this.journalSourceId = body.journalSourceId();
        this.journalSourceType = body.journalSourceType();
        this.journal = body.journal();
        this.issnL = body.issnL();
        this.publisher = body.publisher();
        this.citedByCount = body.citedByCount();
        this.crossrefUpdates = crossrefUpdates;
        this.authors = authors;
        this.sourceStatus = sourceStatus;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public UUID getPaperId() {
        return paperId;
    }

    public String getDoi() {
        return doi;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public String getOpenalexId() {
        return openalexId;
    }

    public String getTitle() {
        return title;
    }

    public Integer getPublicationYear() {
        return publicationYear;
    }

    public Boolean getIsRetracted() {
        return isRetracted;
    }

    public Boolean getInDoaj() {
        return inDoaj;
    }

    public String getJournalSourceId() {
        return journalSourceId;
    }

    public String getJournalSourceType() {
        return journalSourceType;
    }

    public String getJournal() {
        return journal;
    }

    public String getIssnL() {
        return issnL;
    }

    public String getPublisher() {
        return publisher;
    }

    public Integer getCitedByCount() {
        return citedByCount;
    }

    public String getCrossrefUpdates() {
        return crossrefUpdates;
    }

    public String getAuthors() {
        return authors;
    }

    public String getSourceStatus() {
        return sourceStatus;
    }
}
