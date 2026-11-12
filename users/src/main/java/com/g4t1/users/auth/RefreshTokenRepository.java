package com.g4t1.users.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // conditional on used_at being null, so of two uses at once exactly one marks it (returns 1)
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(UUID id, Instant now);

    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.usedAt = :now where t.tokenHash = :tokenHash and t.usedAt is null")
    int markUsedByHash(String tokenHash, Instant now);
}
