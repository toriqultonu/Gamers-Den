package dev.gamersden.common.spi;

import java.util.List;

/**
 * The narrow write the {@code tournament} package needs from {@code billing} — the direct
 * {@code POST /tournaments/{id}/entries} sale (api-contract.md, Tournaments), which takes the fee
 * and registers the player in one call rather than riding along on a seat's bill.
 *
 * <p>It is the same settle either way. This door exists so that the counter route does not grow a
 * second money path: {@code billing} still writes the transaction, the tenders, the loyalty and
 * the print job in one transaction (invariant §5.3), and {@code tournament} still registers the
 * player through {@link TournamentEntrySettlement} inside it. All this interface adds is a way to
 * start that from a tournament-shaped request.
 *
 * <p>{@link TenderLine} carries the payment method as a string so {@code common} stays free of the
 * {@code billing} enum — the implementation parses it and answers 400 on an unknown one, exactly
 * as bean validation would have.
 */
public interface TournamentEntrySale {

    /**
     * Sells one entry at the counter.
     *
     * @param playerName what goes on the stub, or {@code null} for "Walk-in guest"
     * @return the sale, the print job and the seed the player was given
     */
    Sold sell(long tournamentId, String playerName, List<TenderLine> tenders);

    /** One split row: {@code CASH|BKASH|NAGAD|WALLET}, the amount, and the TrxID where required. */
    record TenderLine(String method, int amount, String paymentRef) {
    }

    /** @param qrToken the opaque payload of the P5 QR (docs/tournaments.md §7) */
    record Sold(long transactionId,
                String publicId,
                long printJobId,
                long entryId,
                int seed,
                String qrToken) {
    }
}
