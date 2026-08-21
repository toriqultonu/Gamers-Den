package dev.gamersden.member.repo;

import dev.gamersden.member.domain.Member;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);

    boolean existsByPhone(String phone);

    /**
     * The row lock every wallet and points write takes first. The ledger is the source of truth and
     * {@code members.wallet}/{@code members.points} are its running totals — two concurrent
     * top-ups must not read the same total and both write it back (task B08: ledger and column
     * consistent, in one transaction).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);

    /**
     * {@code GET /members?q=} — one box over both columns: the name matches anywhere,
     * case-insensitively, and the phone matches on digits so an operator can type the separators
     * they see on the customer's screen. A term with no digits in it never falls through to the
     * phone half.
     */
    @Query("""
            SELECT m FROM Member m
            WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', :text, '%'))
               OR (:digits <> '' AND m.phone LIKE CONCAT('%', :digits, '%'))
            """)
    Page<Member> search(@Param("text") String text, @Param("digits") String digits, Pageable pageable);
}
