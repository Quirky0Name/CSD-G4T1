package com.g4t1.storage.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // Impact's evaluation, written once: conditional on the report still being investigated, so of two
    // writes at once only one lands, and an investigating or assessed report is never touched
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Report r set r.changeSummary = :changeSummary, r.changeSeverity = :changeSeverity,
                r.impactLevel = :impactLevel, r.evaluation = :evaluation, r.recommendation = :recommendation,
                r.assessment = :assessment, r.evaluatedAt = :evaluatedAt, r.status = :assessed
            where r.id = :id and r.status = :investigated""")
    int recordEvaluation(@Param("id") long id,
                         @Param("changeSummary") String changeSummary,
                         @Param("changeSeverity") AssessmentLevel changeSeverity,
                         @Param("impactLevel") AssessmentLevel impactLevel,
                         @Param("evaluation") String evaluation,
                         @Param("recommendation") String recommendation,
                         @Param("assessment") String assessment,
                         @Param("evaluatedAt") Instant evaluatedAt,
                         @Param("assessed") ReportStatus assessed,
                         @Param("investigated") ReportStatus investigated);
}
