package com.g4t1.users.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    boolean existsByOwnerIdAndName(UUID ownerId, String name);

    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);

    // as UserRepository.deleteIfPresent: a second delete of the same project does nothing instead of failing
    @Modifying
    @Transactional
    @Query("delete from Project p where p.id = :id")
    void deleteIfPresent(UUID id);
}
