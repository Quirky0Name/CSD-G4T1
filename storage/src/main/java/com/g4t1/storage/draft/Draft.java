package com.g4t1.storage.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// The researcher's draft for a project. One row per (owner, project); a new upload swaps the file.
@Entity
@Table(name = "drafts")
public class Draft {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID ownerId;

    // null = the owner's default project
    private UUID projectId;

    // key into the file store
    @Column(nullable = false)
    private String fileKey;

    private String filename;

    @Column(nullable = false)
    private Instant uploadedAt;

    protected Draft() {
    }

    public Draft(UUID ownerId, UUID projectId, String fileKey, String filename) {
        this.ownerId = ownerId;
        this.projectId = projectId;
        replaceFile(fileKey, filename);
    }

    public void replaceFile(String fileKey, String filename) {
        this.fileKey = fileKey;
        this.filename = filename;
        // Postgres keeps microseconds; truncating makes the upload response match later reads
        this.uploadedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFilename() {
        return filename;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
