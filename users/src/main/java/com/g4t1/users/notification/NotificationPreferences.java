package com.g4t1.users.notification;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

// One researcher's outside notification preferences; the account's id is the key, so there's one per account
@Entity
@Table(name = "notification_preferences")
public class NotificationPreferences {

    @Id
    private UUID userId;

    private boolean emailEnabled;

    private String emailMinAlertSeverity;

    private String emailMinChangeSeverity;

    private String emailMinImpactLevel;

    private boolean telegramEnabled;

    private Long telegramChatId;

    private String telegramMinAlertSeverity;

    private String telegramMinChangeSeverity;

    private String telegramMinImpactLevel;

    protected NotificationPreferences() {
    }

    // the defaults: both channels off, no chat id, and each threshold at high, medium and medium
    public NotificationPreferences(UUID userId) {
        this.userId = userId;
        this.emailMinAlertSeverity = "high";
        this.emailMinChangeSeverity = "medium";
        this.emailMinImpactLevel = "medium";
        this.telegramMinAlertSeverity = "high";
        this.telegramMinChangeSeverity = "medium";
        this.telegramMinImpactLevel = "medium";
    }

    public void replaceEmail(EmailRequest channel) {
        this.emailEnabled = channel.enabled();
        this.emailMinAlertSeverity = channel.minAlertSeverity();
        this.emailMinChangeSeverity = channel.minChangeSeverity();
        this.emailMinImpactLevel = channel.minImpactLevel();
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public String getEmailMinAlertSeverity() {
        return emailMinAlertSeverity;
    }

    public String getEmailMinChangeSeverity() {
        return emailMinChangeSeverity;
    }

    public String getEmailMinImpactLevel() {
        return emailMinImpactLevel;
    }

    public boolean isTelegramEnabled() {
        return telegramEnabled;
    }

    public Long getTelegramChatId() {
        return telegramChatId;
    }

    public String getTelegramMinAlertSeverity() {
        return telegramMinAlertSeverity;
    }

    public String getTelegramMinChangeSeverity() {
        return telegramMinChangeSeverity;
    }

    public String getTelegramMinImpactLevel() {
        return telegramMinImpactLevel;
    }
}
