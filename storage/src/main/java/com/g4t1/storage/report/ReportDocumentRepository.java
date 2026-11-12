package com.g4t1.storage.report;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportDocumentRepository extends JpaRepository<ReportDocument, Long> {

    Optional<ReportDocument> findByReportIdAndDoi(Long reportId, String doi);

    List<ReportDocument> findByReportIdOrderByIdAsc(Long reportId);

    // the documents of several reports in one query, for the frontend's list of a paper's reports
    List<ReportDocument> findByReportIdInOrderByIdAsc(Collection<Long> reportIds);

    // the document, when its report's paper belongs to the owner
    @Query("""
            select d from ReportDocument d, Report r, Paper p
            where d.id = :documentId and r.id = d.reportId and p.id = r.paperId and p.ownerId = :ownerId
            """)
    Optional<ReportDocument> findOwned(long documentId, UUID ownerId);
}
