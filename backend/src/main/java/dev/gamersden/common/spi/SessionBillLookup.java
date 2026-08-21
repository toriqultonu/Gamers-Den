package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The narrow read the {@code billing} package needs from {@code session} — the blocks behind
 * {@code GET /sessions/{id}/bill} — without reaching for {@code SessionBlockRepository}
 * (ARCHITECTURE.md §3: no cross-package repository access, call the owning package's service).
 *
 * <p>Deliberately raw: the door hands over one entry per live block with its snapshot price and
 * whether it is already paid for, and the bill does the arithmetic. Keeping the "unbilled blocks
 * only" rule inside the bill — rather than pre-summed here — is what lets it be unit-tested
 * without a database (TASKLIST B09: pure computation service).
 *
 * <p>Implemented by {@code session/domain/SessionBillLookupService}; read-only.
 */
public interface SessionBillLookup {

    /** The session's billable state, or empty when the id is unknown. */
    Optional<BillableSession> findForBill(long sessionId);

    /**
     * One session as the bill reads it.
     *
     * @param state      the stored {@code sessions.state} — a CLOSED seat still has a readable bill
     * @param blocks     non-removed blocks, oldest first; removed ones were never sold
     * @param serverTime the instant the read was taken, so the panel never trusts its own clock
     */
    record BillableSession(long sessionId,
                           long stationId,
                           Long memberId,
                           String state,
                           List<TimeBlock> blocks,
                           OffsetDateTime serverTime) {
    }

    /**
     * One 30-minute block.
     *
     * @param price the snapshot it was sold at — a later {@code PUT /pricing} never reaches it
     * @param paid  true once it carries a {@code paid_tx_id}: prepaid at a booking or play-ticket
     *              sale, or settled mid-session. Either way it is never billed again (§5.9)
     */
    record TimeBlock(int price, boolean paid) {
    }
}
