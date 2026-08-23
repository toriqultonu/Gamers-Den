package dev.gamersden.queue.repo;

import dev.gamersden.queue.domain.TokenSeq;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface TokenSeqRepository extends JpaRepository<TokenSeq, LocalDate> {

    /**
     * Hands out the next token of {@code day} and leaves the counter one ahead — the row-locked
     * upsert invariant §5.10 asks for, written the way the V001 schema comment describes it.
     *
     * <p>One statement rather than "read, then update": {@code ON CONFLICT DO UPDATE} takes the
     * row's write lock and holds it to commit, so a second terminal allocating at the same instant
     * blocks here and then re-reads the value this one wrote. That is what makes two concurrent
     * check-ins take #1 and #2 rather than both taking #1 and colliding at
     * {@code UNIQUE (token_date, token_no)} with the money already written.
     *
     * <p>The counter is keyed by date, so it restarts on its own at Asia/Dhaka midnight; yesterday
     * row and today's never contend.
     */
    @Query(value = """
            INSERT INTO token_seq (token_date, next_no) VALUES (:day, 2)
            ON CONFLICT (token_date) DO UPDATE SET next_no = token_seq.next_no + 1
            RETURNING next_no - 1
            """, nativeQuery = true)
    int allocate(@Param("day") LocalDate day);
}
