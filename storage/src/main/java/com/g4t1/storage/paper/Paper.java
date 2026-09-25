package com.g4t1.storage.paper;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "papers")
public class Paper {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    private UUID folderId;

    private String doi;
    private String openalexId;
    private String title;
    private String journal;
    private String issn;
    private Integer publicationYear;

    // key into the file store; null for a DOI-only paper
    private String fileKey;

    @Column(nullable = false)
    private Instant createdAt;

    protected Paper() {
    }

    public Paper(UUID ownerId) {
        this.ownerId = ownerId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean hasFile() {
        return fileKey != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getFolderId() {
        return folderId;
    }

    public void setFolderId(UUID folderId) {
        this.folderId = folderId;
    }

    public String getDoi() {
        return doi;
    }

    public void setDoi(String doi) {
        this.doi = doi;
    }

    public String getOpenalexId() {
        return openalexId;
    }

    public void setOpenalexId(String openalexId) {
        this.openalexId = openalexId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getJournal() {
        return journal;
    }

    public void setJournal(String journal) {
        this.journal = journal;
    }

    public String getIssn() {
        return issn;
    }

    public void setIssn(String issn) {
        this.issn = issn;
    }

    public Integer getPublicationYear() {
        return publicationYear;
    }

    public void setPublicationYear(Integer publicationYear) {
        this.publicationYear = publicationYear;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
