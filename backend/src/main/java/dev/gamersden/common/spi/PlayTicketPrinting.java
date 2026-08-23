package dev.gamersden.common.spi;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The narrow write {@code booking} (and, from B16, {@code queue}) needs from {@code printing} —
 * P6, the play-ticket stub that carries a daily queue token (design.md §5, docs/bookings.md §2).
 *
 * <p>P6 rides along on a sale receipt when a play ticket is sold at the POS, but a booking
 * check-in takes no money, so there is no receipt for it to ride on: invariant §5.5 spells that
 * case out — "booking check-in renders P6 standalone". This is that door.
 *
 * <p>Same two rules as every other print door: {@link
 * org.springframework.transaction.annotation.Propagation#MANDATORY}, so the job is created inside
 * the transaction that issued the token and a rolled-back check-in leaves no paper behind (§5.3);
 * and the bytes are rendered once, here, so a retry re-sends exactly what was printed (§5.5).
 */
public interface PlayTicketPrinting {

    /** Renders and queues one P6 stub, in the caller's transaction. */
    long issuePlayTicket(PlayTicket ticket);

    /**
     * Everything P6 prints.
     *
     * @param queueEntryId the token's row — the Code 128 payload, and what the job references
     * @param tokenNo      printed double-height as {@code TOKEN #NN}
     * @param prebooked    true for a checked-in booking ("PLAY TICKET — PREBOOKED"), false for a
     *                     walk-up ticket sold at the counter
     * @param startAt      the booked slot, or {@code null} on a walk-up ticket
     * @param deviceId     which printer the job is queued for — the terminal owns its USB printer
     * @param operatorId   the staff member who checked the customer in, for the print-job audit
     */
    record PlayTicket(long queueEntryId,
                      int tokenNo,
                      LocalDate tokenDate,
                      String playerName,
                      String consoleType,
                      int blocks,
                      boolean prebooked,
                      String stationName,
                      OffsetDateTime startAt,
                      String deviceId,
                      long operatorId,
                      OffsetDateTime at) {
    }
}
