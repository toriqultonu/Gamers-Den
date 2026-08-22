package dev.gamersden.billing.repo;

import dev.gamersden.billing.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByPublicId(String publicId);

    List<Transaction> findByShiftId(Long shiftId);

    /**
     * How many public ids the venue day has already handed out. Counted over {@code public_id}
     * rather than {@code created_at} on purpose: the id <em>is</em> the sequence, so the number is
     * derived from the same column it has to be unique in, and a row inserted with the database's
     * {@code now()} can never be counted against a different day than the one it is named for.
     */
    @Query("SELECT count(t) FROM Transaction t WHERE t.publicId LIKE :prefix")
    long countWithPublicIdPrefix(@Param("prefix") String prefix);

    /**
     * Serialises public-id allocation for the rest of the caller's transaction. Two settles racing
     * for the same day's next number must queue here, or both would read the same count and one
     * would lose at the UNIQUE index — after having already written its money rows.
     *
     * <p>A transaction-scoped advisory lock rather than a counter table: nothing in the documented
     * schema counts transactions (ARCHITECTURE.md §4.1), and {@code token_seq} is the daily
     * <em>queue</em> counter shared by bookings and play tickets (invariant §5.10), not this.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(CAST(:key AS bigint))) AS held",
            nativeQuery = true)
    int lockPublicIdSequence(@Param("key") long key);
}
