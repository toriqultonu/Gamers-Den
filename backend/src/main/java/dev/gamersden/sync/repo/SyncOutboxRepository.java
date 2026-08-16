package dev.gamersden.sync.repo;

import dev.gamersden.sync.domain.SyncOutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SyncOutboxRepository extends JpaRepository<SyncOutboxEntry, Long> {

    List<SyncOutboxEntry> findByPushedAtIsNullOrderByIdAsc();
}
