package dev.gamersden.queue.repo;

import dev.gamersden.queue.domain.QueueEntry;
import dev.gamersden.queue.domain.QueueEntryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface QueueEntryRepository extends JpaRepository<QueueEntry, Long> {

    /**
     * The queue rail: everyone still waiting, oldest counter first (docs/bookings.md §3).
     *
     * <p>Deliberately not filtered to today. A token issued yesterday and never seated keeps
     * working after the rollover — the entry id is the key, not the number on the paper
     * (docs/bookings.md §7, invariant §5.10) — so it stays at the head of the rail, and its
     * {@code tokenDate} is what tells the operator it is an old one.
     */
    List<QueueEntry> findByStatusOrderByTokenDateAscTokenNoAsc(QueueEntryStatus status);

    /** The rail's history strip: tokens seated on a given day, in the order they were issued. */
    List<QueueEntry> findByTokenDateAndStatusOrderByTokenNoAsc(LocalDate tokenDate,
                                                              QueueEntryStatus status);

    /** Every token issued by one sale — what a void or a replay has to be able to find again. */
    List<QueueEntry> findByTxIdOrderByIdAsc(Long txId);

    /**
     * The row locked for the rest of the caller's transaction — how seating and no-show removal
     * stop two terminals from acting on the same token at once. The WAITING guard either side of
     * this read only binds if nobody can move the status in between.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM QueueEntry q WHERE q.id = :id")
    Optional<QueueEntry> findByIdForUpdate(@Param("id") long id);
}
