package com.g4t1.storage.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// one entry in the researcher's log of notes on an alert; notes are never edited or deleted
@Entity
@Table(name = "alert_notes")
public class AlertNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long alertId;

    @Column(nullable = false)
    private String text;

    @Column(nullable = false)
    private Instant createdAt;

    protected AlertNote() {
    }

    public AlertNote(Long alertId, String text) {
        this.alertId = alertId;
        this.text = text;
    }

    @PrePersist
    void onCreate() {
        // Postgres keeps microseconds; truncating makes the POST response match later reads
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getAlertId() {
        return alertId;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
