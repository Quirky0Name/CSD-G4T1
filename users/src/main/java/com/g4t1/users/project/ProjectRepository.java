package com.g4t1.users.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    boolean existsByOwnerIdAndName(UUID ownerId, String name);

    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);
}
