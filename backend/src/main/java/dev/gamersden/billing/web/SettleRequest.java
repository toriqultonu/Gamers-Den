package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.PaymentMethod;
import dev.gamersden.billing.domain.Tender;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * The body of {@code POST /payments} (api-contract.md, "Billing &amp; payments"):
 *
 * <pre>{@code
 * { "target": { "sessionId": 12 },
 *   "redeemPoints": 100,
 *   "splits": [ { "method": "CASH", "amount": 220 },
 *               { "method": "BKASH", "amount": 100, "paymentRef": "8XK21QW7" } ] }
 * }</pre>
 *
 * <p>Bean validation only covers what is true of any request — presence, sign, shape. Everything
 * that depends on what is actually owed (the splits summing, the wallet floor, the points cap, the
 * bKash reference) is domain truth and lives in {@code Settlement}, where it can be tested without
 * a database and answers with the canonical 409 codes rather than a 400.
 *
 * @param tournamentEntries entries sold with this payment — B12. The field is part of the contract
 *                          today so the FE's request shape never has to change, but a non-empty
 *                          list is refused rather than dropped: a customer must not be able to pay
 *                          for a registration nothing records
 * @param playTickets       play-queue tickets sold with this payment — B16, on the same terms
 */
@Schema(name = "SettleRequest", description = "Settle a session's bill or a counter cart")
public record SettleRequest(@NotNull @Valid Target target,
                            @PositiveOrZero Integer redeemPoints,
                            @Valid List<TournamentEntryRequest> tournamentEntries,
                            @Valid List<PlayTicketRequest> playTickets,
                            @Valid List<SplitRequest> splits) {

    /** Exactly one of the two is given; sending both, or neither, is 400. */
    @Schema(name = "SettleTarget")
    public record Target(Long sessionId, Long cartId) {
    }

    /**
     * One tender row.
     *
     * @param paymentRef the customer's bKash/Nagad TrxID — required on those methods (409
     *                   {@code PAYMENT_REF_REQUIRED}), ignored on cash and wallet
     */
    @Schema(name = "PaymentSplit")
    public record SplitRequest(@NotNull PaymentMethod method, int amount, String paymentRef) {

        Tender toTender() {
            return new Tender(method, amount, paymentRef);
        }
    }

    /** {@code tournamentEntries[]} — registers a player and returns a seed token (B12). */
    @Schema(name = "TournamentEntryRequest")
    public record TournamentEntryRequest(@NotNull Long tournamentId, String playerName) {
    }

    /** {@code playTickets[]} — sells prepaid time and returns a daily queue token (B16). */
    @Schema(name = "PlayTicketRequest")
    public record PlayTicketRequest(@NotNull String consoleType, int blocks, String playerName) {
    }

    public List<Tender> tenders() {
        return splits == null ? List.of() : splits.stream().map(SplitRequest::toTender).toList();
    }
}
