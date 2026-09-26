package com.g4t1.storage.snapshot;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {

    List<Snapshot> findByPaperIdAndSnapshotIdGreaterThanOrderBySnapshotIdAsc(UUID paperId, long afterId, Limit limit);
}
