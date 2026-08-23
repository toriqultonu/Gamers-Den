package dev.gamersden.queue.repo;

import dev.gamersden.queue.domain.QueueEntry;
import dev.gamersden.queue.domain.QueueEntryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

    /** The queue rail, in token order (docs/bookings.md §3) — B16 reads it. */
    List<QueueEntry> findByTokenDateAndStatusOrderByTokenNoAsc(LocalDate tokenDate,
                                                              QueueEntryStatus status);
}
