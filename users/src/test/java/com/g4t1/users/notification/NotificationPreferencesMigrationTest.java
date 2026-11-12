package com.g4t1.users.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// V6 on a database that already has accounts: its own in-memory database, migrated to V5, given an
// account, then migrated on, since the app's tests only ever migrate an empty one
class NotificationPreferencesMigrationTest {

    @Test
    void anAccountMadeBeforeV6GetsTheDefaultRow() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:before_v6;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        Flyway.configure().dataSource(dataSource).schemas("users").target("5").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users.users (id, email, password_hash, created_at) values (?, ?, ?, current_timestamp)",
                id, "old@example.com", "hash");

        Flyway.configure().dataSource(dataSource).schemas("users").load().migrate();

        Map<String, Object> row = jdbc.queryForMap("select * from users.notification_preferences where user_id = ?", id);
        assertThat(row).containsEntry("email_enabled", false)
                .containsEntry("email_min_alert_severity", "high")
                .containsEntry("email_min_change_severity", "medium")
                .containsEntry("email_min_impact_level", "medium")
                .containsEntry("telegram_enabled", false)
                .containsEntry("telegram_chat_id", null)
                .containsEntry("telegram_min_alert_severity", "high")
                .containsEntry("telegram_min_change_severity", "medium")
                .containsEntry("telegram_min_impact_level", "medium");
    }
}
