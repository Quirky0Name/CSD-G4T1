package com.g4t1.users.notification;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {

    // for a channel's PUT: overlapping PUTs queue on the row, so each one writes a whole channel
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from NotificationPreferences p where p.userId = :userId")
    Optional<NotificationPreferences> findForUpdate(@Param("userId") UUID userId);
}
