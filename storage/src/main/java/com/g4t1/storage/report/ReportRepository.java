package com.g4t1.storage.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, Long> {

    // newest first, for the frontend; the id breaks a tie in created_at
    List<Report> findByPaperIdOrderByCreatedAtDescIdDesc(UUID paperId);
}
