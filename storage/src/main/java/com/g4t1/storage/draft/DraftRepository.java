package com.g4t1.storage.draft;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DraftRepository extends JpaRepository<Draft, UUID> {

    List<Draft> findByOwnerId(UUID ownerId);

    // a null projectId finds the owner's default project (the derived query uses IS NULL)
    Optional<Draft> findByOwnerIdAndProjectId(UUID ownerId, UUID projectId);

    // as above, locked until the transaction ends, so uploads and deletes on one project go one at a time
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Draft> findForUpdateByOwnerIdAndProjectId(UUID ownerId, UUID projectId);
}
