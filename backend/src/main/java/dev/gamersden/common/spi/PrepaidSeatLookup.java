package dev.gamersden.common.spi;

import java.util.Optional;

/**
 * The narrow read the {@code session} package needs from {@code queue} — the prepaid seat behind a
 * {@code bookingId} or {@code queueEntryId} on {@code POST /sessions} (api-contract.md, Sessions).
 *
 * <p>Invariant §5.9: seating a token is one transaction — the session row, its prepaid blocks born
 * paid (carrying the original sale's {@code paid_tx_id}), and the token flipped to consumed. The
 * session half of that transaction is implemented in {@code SessionService}; this interface is the
 * other half, implemented by {@code queue/domain/PrepaidSeatLookupService}.
 *
 * <p>The token has to be one that can still be sat down. A queue entry already SEATED or refunded,
 * and a booking that has not checked in yet, are 409 rather than empty: the operator has picked
 * the wrong row, and "not found" would send them looking for a typo instead.
 */
public interface PrepaidSeatLookup {

    /** The prepaid seat a PAID, checked-in booking holds, or empty when there is none. */
    Optional<PrepaidSeat> findByBooking(long bookingId);

    /** The prepaid seat a WAITING play-queue entry holds, or empty when there is none. */
    Optional<PrepaidSeat> findByQueueEntry(long queueEntryId);

    /**
     * Marks the token used, inside the caller's transaction: queue entry → SEATED (carrying the
     * session it was seated on) and, for a booking, booking → USED.
     */
    void consume(PrepaidSeat seat, long sessionId);

    /**
     * One paid-for seat waiting to be filled.
     *
     * @param queueEntryId the {@code queue_entries} row — what {@code sessions.queue_entry_id} keeps
     * @param bookingId    the booking behind it, or {@code null} for a walk-up play ticket
     * @param consoleType  {@code PS5|PS4}; a different seat is 409 {@code CONSOLE_TYPE_MISMATCH}
     * @param blocks       30-minute blocks already paid for
     * @param blockPrice   the rate snapshot they were sold at
     * @param paidTxId     the sale transaction every prepaid block is born carrying
     */
    record PrepaidSeat(long queueEntryId,
                       Long bookingId,
                       String consoleType,
                       int blocks,
                       int blockPrice,
                       long paidTxId,
                       Long memberId) {
    }
}
