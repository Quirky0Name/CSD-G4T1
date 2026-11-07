package com.g4t1.users.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    List<User> findByEmailNotOrderByCreatedAtDesc(String email);

    // one delete statement, so a second delete that finds the row already gone does nothing;
    // deleteById reads the row first and fails when another delete removed it in between
    @Modifying
    @Transactional
    @Query("delete from User u where u.id = :id")
    void deleteIfPresent(UUID id);

    @Modifying
    @Transactional
    @Query("delete from User u where u.email = :email")
    void deleteByEmail(String email);
}
