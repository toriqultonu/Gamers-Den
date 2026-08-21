package dev.gamersden.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * {@code refresh_tokens} — one row per issued refresh cookie. Rotation revokes the row and points
 * {@code rotatedTo} at its successor, so a replayed cookie is recognisable as reuse rather than
 * merely unknown. Only the SHA-256 digest of the cookie value is stored.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Column(nullable = false)
    private String terminal;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Generated(event = EventType.INSERT)
    @Column(name = "issued_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "rotated_to")
    private Long rotatedTo;

    protected RefreshToken() {
    }

    public RefreshToken(Long staffId, String terminal, String tokenHash, OffsetDateTime expiresAt) {
        this.staffId = staffId;
        this.terminal = terminal;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getStaffId() {
        return staffId;
    }

    public String getTerminal() {
        return terminal;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public Long getRotatedTo() {
        return rotatedTo;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(OffsetDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /** Live = presented before it expired and not already spent, logged out or family-revoked. */
    public boolean isUsableAt(OffsetDateTime now) {
        return !isRevoked() && !isExpiredAt(now);
    }

    public void revoke(OffsetDateTime at) {
        if (revokedAt == null) {
            revokedAt = at;
        }
    }

    public void rotateTo(Long successorId) {
        this.rotatedTo = successorId;
    }
}
