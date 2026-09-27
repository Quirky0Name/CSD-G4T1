package com.g4t1.storage.research;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface ResearchPaperRepository extends JpaRepository<ResearchPaper, UUID> {

    // a null folderId finds the owner's "no folder" project (the derived query uses IS NULL)
    Optional<ResearchPaper> findByOwnerIdAndFolderId(UUID ownerId, UUID folderId);

    // as above, locked until the transaction ends, so uploads and deletes on one project go one at a time
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ResearchPaper> findForUpdateByOwnerIdAndFolderId(UUID ownerId, UUID folderId);
}
