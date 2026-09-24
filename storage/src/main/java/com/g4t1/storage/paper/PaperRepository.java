package com.g4t1.storage.paper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PaperRepository extends JpaRepository<Paper, UUID> {

    boolean existsByOwnerIdAndDoi(UUID ownerId, String doi);
}
