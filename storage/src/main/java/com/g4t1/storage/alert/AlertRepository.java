package com.g4t1.storage.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByPaperIdAndChangeKey(UUID paperId, String changeKey);

    // only the keys, so Research Evaluation can skip changes it has already evaluated without loading whole alerts
    @Query("select a.changeKey from Alert a where a.paperId = :paperId order by a.changeKey")
    List<String> findChangeKeysByPaperId(@Param("paperId") UUID paperId);

    // unordered; AlertService sorts the list, since the order depends on severity
    List<Alert> findByPaperId(UUID paperId);

    List<Alert> findByPaperIdAndStatusNot(UUID paperId, AlertStatus status);

    boolean existsByPaperIdAndReportIdIsNull(UUID paperId);

    // one conditional update, so two reports opened at once can't both take an alert: the second
    // waits on the first's row locks, then finds report_id already set and updates nothing
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Alert a set a.reportId = :reportId where a.paperId = :paperId and a.reportId is null")
    int assignUnreportedToReport(@Param("paperId") UUID paperId, @Param("reportId") long reportId);

    List<Alert> findByReportIdOrderByIdAsc(Long reportId);

    // A late retraction notice replaces a notice-less retraction alert and makes it new again: the
    // row keeps its id (and so its notes), leaves its report so the next one takes it, and gets the
    // notice's assessment. Conditional on the notice still missing, so only one request replaces it.
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Alert a set a.severity = :severity, a.description = :description,
                a.recommendation = :recommendation, a.noticeDoi = :noticeDoi, a.detectedAt = :detectedAt,
                a.snapshotId = :snapshotId, a.previousSnapshotId = :previousSnapshotId,
                a.status = :status, a.statusChangedAt = null, a.reportId = null
            where a.id = :id and a.noticeDoi is null""")
    int replaceNoticelessRetraction(@Param("id") long id,
                                    @Param("severity") Severity severity,
                                    @Param("description") String description,
                                    @Param("recommendation") String recommendation,
                                    @Param("noticeDoi") String noticeDoi,
                                    @Param("detectedAt") Instant detectedAt,
                                    @Param("snapshotId") long snapshotId,
                                    @Param("previousSnapshotId") long previousSnapshotId,
                                    @Param("status") AlertStatus status);
}
