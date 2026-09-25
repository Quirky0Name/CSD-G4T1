package com.g4t1.storage.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByPaperIdAndChangeKey(UUID paperId, String changeKey);

    // newest first; alerts from the same snapshot pair share detected_at, so id breaks the tie
    List<Alert> findByPaperIdOrderByDetectedAtDescIdDesc(UUID paperId);

    List<Alert> findByPaperIdAndStatusNotOrderByDetectedAtDescIdDesc(UUID paperId, AlertStatus status);
}
