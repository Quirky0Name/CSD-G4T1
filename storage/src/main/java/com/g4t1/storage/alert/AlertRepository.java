package com.g4t1.storage.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByPaperIdAndChangeKey(UUID paperId, String changeKey);

    // only the keys, so Research Evaluation can skip changes it has already evaluated without loading whole alerts
    @Query("select a.changeKey from Alert a where a.paperId = :paperId order by a.changeKey")
    List<String> findChangeKeysByPaperId(@Param("paperId") UUID paperId);

    // newest first; alerts from the same snapshot pair share detected_at, so id breaks the tie
    List<Alert> findByPaperIdOrderByDetectedAtDescIdDesc(UUID paperId);

    List<Alert> findByPaperIdAndStatusNotOrderByDetectedAtDescIdDesc(UUID paperId, AlertStatus status);
}
