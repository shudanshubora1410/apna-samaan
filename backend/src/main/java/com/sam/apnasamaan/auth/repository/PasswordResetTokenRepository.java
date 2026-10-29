package com.sam.apnasamaan.auth.repository;

import com.sam.apnasamaan.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    @Query("SELECT prt FROM PasswordResetToken prt JOIN FETCH prt.user WHERE prt.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashWithUser(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("UPDATE PasswordResetToken prt SET prt.usedAt = :now WHERE prt.user.id = :userId AND prt.usedAt IS NULL")
    int invalidateUnusedTokensForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
