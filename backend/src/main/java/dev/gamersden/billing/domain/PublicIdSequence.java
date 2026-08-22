package dev.gamersden.billing.domain;

import dev.gamersden.billing.repo.TransactionRepository;
import dev.gamersden.common.config.VenueTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The {@code GD-ddMM-NNN} allocator every transaction row goes through — sales, voids and refunds
 * alike. One place, because the number has to be unique across all three and the way it is made
 * unique is a lock that only works if everybody takes it.
 *
 * <p>The advisory lock is taken first and held to commit, so the count and the insert that follows
 * it cannot be interleaved by a second terminal (see
 * {@link TransactionRepository#lockPublicIdSequence}). {@link Propagation#MANDATORY} for the same
 * reason: a number handed out in its own transaction would be released the moment it was read.
 */
@Component
public class PublicIdSequence {

    /**
     * The advisory-lock key allocation serialises on. An arbitrary constant — all that matters is
     * that every terminal in the venue picks the same one.
     */
    private static final long PUBLIC_ID_LOCK = 0x6764_7478L; // "gdtx"

    private final TransactionRepository transactions;

    public PublicIdSequence(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** The next id of the venue day {@code at} falls in. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(OffsetDateTime at) {
        transactions.lockPublicIdSequence(PUBLIC_ID_LOCK);
        LocalDate day = at.atZoneSameInstant(VenueTime.ZONE).toLocalDate();
        String prefix = TransactionPublicId.dayPrefix(day);
        return TransactionPublicId.of(day, transactions.countWithPublicIdPrefix(prefix + "%") + 1);
    }
}
