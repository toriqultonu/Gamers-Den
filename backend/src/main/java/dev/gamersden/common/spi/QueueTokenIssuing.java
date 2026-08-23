package dev.gamersden.common.spi;

import java.time.LocalDate;

/**
 * The narrow write the {@code booking} package needs from {@code queue} — the daily token a
 * check-in issues — without reaching for {@code TokenSeqRepository} or {@code queue_entries}
 * (ARCHITECTURE.md §3: no cross-package repository access, call the owning package's service).
 *
 * <p>Invariant §5.10: one counter, shared by bookings and play tickets, allocated by a row-locked
 * upsert on {@code token_seq} and keyed by the venue day so it resets at Asia/Dhaka midnight. B16
 * sells walk-up play tickets through this same door with {@code source = PLAY_TICKET}; there is
 * deliberately no second place a token can come from.
 *
 * <p>Implemented by {@code queue/domain/QueueTokenService} with
 * {@link org.springframework.transaction.annotation.Propagation#MANDATORY} — a token belongs to
 * the transaction that issued it, so a check-in that rolls back cannot burn a number or leave a
 * queue entry standing without its booking.
 */
public interface QueueTokenIssuing {

    /** Allocates the next token of the current venue day and writes its queue entry. */
    IssuedToken issue(TokenRequest request);

    /**
     * @param source      {@code BOOKING|PLAY_TICKET} — a string here so {@code common} stays free
     *                    of the {@code queue} package's enum
     * @param bookingId   the booking being checked in, or {@code null} for a walk-up ticket
     * @param txId        the sale that paid for the time behind the token (invariant §5.7)
     * @param consoleType {@code PS5|PS4}; enforced against the seat when the token is seated
     * @param blocks      30-minute blocks already paid for
     */
    record TokenRequest(String source,
                        Long bookingId,
                        long txId,
                        String playerName,
                        String consoleType,
                        int blocks) {
    }

    /**
     * @param queueEntryId the row id — the key that keeps a token working across a day rollover
     * @param tokenNo      the daily sequence, printed as {@code TOKEN #NN}
     * @param tokenDate    the venue day the number was counted against
     */
    record IssuedToken(long queueEntryId, int tokenNo, LocalDate tokenDate) {
    }
}
