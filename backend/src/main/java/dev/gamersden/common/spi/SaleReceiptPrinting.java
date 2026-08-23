package dev.gamersden.common.spi;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The narrow write {@code billing} (and, from B15, {@code booking}) needs from {@code printing} —
 * the sale ticket a settle queues — without reaching for {@code PrintJobRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>Invariant §5.3: the print job is created <em>inside the money transaction</em>, so a replayed
 * settle returns the same {@code printJobId} and a rolled-back settle leaves no paper behind.
 * Invariant §5.5: the bytes are rendered once, here, and stored — a retry re-sends them
 * unchanged.
 *
 * <p>What is passed is the receipt's content, not its layout. Until B17 the renderer behind this
 * door writes a plain-text placeholder; swapping in the real ESC/POS P1 template changes nothing
 * on this side.
 */
public interface SaleReceiptPrinting {

    /** Renders and queues one sale ticket, in the caller's transaction. */
    long issueSaleReceipt(SaleReceipt receipt);

    /**
     * Everything P1 prints (design.md §5).
     *
     * @param heading    the station the sale belongs to, or "Counter sale"
     * @param deviceId   which printer the job is queued for — the terminal owns its USB printer
     * @param operatorId the cashier, for the CASHIER meta row and the print-job audit
     * @param total      what the tenders come to: charges minus any points discount
     * @param entryStubs the P5 tournament stubs this sale registered, appended to the same job
     *                   (docs/tournaments.md §7); empty on an ordinary sale
     * @param bookingStub the P7 booking confirmation this sale created, appended to the same job
     *                    (docs/bookings.md §2); {@code null} on an ordinary sale
     */
    record SaleReceipt(long transactionId,
                       String publicId,
                       String heading,
                       String deviceId,
                       long operatorId,
                       OffsetDateTime at,
                       List<Line> lines,
                       int total,
                       List<Tender> tenders,
                       int pointsRedeemed,
                       int pointsEarned,
                       Integer pointsBalance,
                       List<EntryStub> entryStubs,
                       BookingStub bookingStub) {

        public SaleReceipt {
            entryStubs = entryStubs == null ? List.of() : List.copyOf(entryStubs);
        }
    }

    /**
     * One P5 tournament stub (design.md §5 P5, docs/tournaments.md §7) — the inverted band, the
     * event and player names, {@code TOKEN #NN} double-height and the QR.
     *
     * @param qrToken the opaque QR payload; it is the ticket, so it is never logged or abbreviated
     *                the way a payment reference is
     */
    record EntryStub(long entryId, String tournamentName, String playerName, int seed, String qrToken) {
    }

    /**
     * The P7 booking confirmation (design.md §5, docs/bookings.md §2) — appended to the sale
     * receipt in the same job, because a booking's receipt and its confirmation are one piece of
     * paper handed over at one counter (invariant §5.5).
     *
     * @param cancellableUntil {@code start_at − cutoff_hours} off the booking's own snapshot; the
     *                         customer is told the deadline they were actually sold
     */
    record BookingStub(long bookingId,
                       String stationName,
                       String consoleType,
                       String playerName,
                       String phone,
                       OffsetDateTime startAt,
                       int blocks,
                       int playAmount,
                       int packageFee,
                       OffsetDateTime cancellableUntil) {
    }

    /** One printed line — {@code GAMING 3x30M}, {@code PEPSI 250ML x2}. */
    record Line(String label, int qty, int amount) {
    }

    /**
     * One tender row.
     *
     * @param paymentRef the provider TrxID on a bKash/Nagad row; only its tail is ever printed or
     *                   logged (invariant §5.12)
     */
    record Tender(String method, int amount, String paymentRef) {
    }
}
