package dev.gamersden.auth.repo;

import dev.gamersden.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** The live family a reuse detection has to burn down. */
    List<RefreshToken> findByStaffIdAndTerminalAndRevokedAtIsNull(Long staffId, String terminal);

    List<RefreshToken> findByStaffIdAndRevokedAtIsNull(Long staffId);

    long deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
