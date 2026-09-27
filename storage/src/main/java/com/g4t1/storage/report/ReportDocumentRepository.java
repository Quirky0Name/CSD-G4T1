package com.g4t1.storage.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReportDocumentRepository extends JpaRepository<ReportDocument, Long> {

    Optional<ReportDocument> findByReportIdAndDoi(Long reportId, String doi);

    List<ReportDocument> findByReportIdOrderByIdAsc(Long reportId);

    // the documents of several reports in one query, for the frontend's list of a paper's reports
    List<ReportDocument> findByReportIdInOrderByIdAsc(Collection<Long> reportIds);
}
