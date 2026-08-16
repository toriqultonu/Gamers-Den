package dev.gamersden.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    void deleteByCreatedAtBefore(OffsetDateTime cutoff);
}
