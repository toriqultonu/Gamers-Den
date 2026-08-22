package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.PaymentService;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code POST /payments} and {@code POST /payments/{id}/void} (api-contract.md, "Billing &amp;
 * payments").
 *
 * <p>Taking money is every operator's job — "Sessions, POS, payments, prints" is ticked for all
 * three roles in the permission matrix (§1). Undoing it is not: voiding someone else's transaction
 * is Manager+, and the API is what enforces that, not the UI.
 *
 * <p>Neither method carries idempotency logic. {@code POST /payments} is on the guarded route list
 * and {@code IdempotencyFilter} handles the whole lifecycle around this controller — missing key →
 * 400, retry → the stored response with {@code Idempotency-Replayed: true}, same key with a
 * different body → 409. The void is deliberately not on that list (api-contract.md §1): it is
 * naturally idempotent through its own 409, because a transaction can only be voided once.
 */
@RestController
@RequestMapping("/payments")
@Tag(name = "Billing")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
            description = "UUID. The first call is stored for 48 h; an identical retry replays it "
                    + "with Idempotency-Replayed: true and the same transactionId / printJobId.")
    @Operation(summary = "Settle a session bill or a counter cart",
            description = "One database transaction: the transaction snapshot and its tenders, the "
                    + "blocks it pays for, the stock it sells, the loyalty it moves, and the "
                    + "receipt print job. The session keeps running — paid blocks simply stop "
                    + "being billable. 409 SPLIT_MISMATCH when the tenders do not equal what is "
                    + "due, WALLET_INSUFFICIENT past the member's balance, PAYMENT_REF_REQUIRED "
                    + "on a bKash/Nagad row with no TrxID; each of them leaves nothing written.")
    public SettleView settle(@Valid @RequestBody SettleRequest request) {
        requireNotYetBuilt("tournamentEntries", request.tournamentEntries(),
                "selling tournament entries lands in B12");
        requireNotYetBuilt("playTickets", request.playTickets(),
                "selling play tickets lands in B16");
        return SettleView.of(payments.settle(request.target().sessionId(),
                request.target().cartId(), request.redeemPoints(), request.tenders()));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Reverse a settled payment in full",
            description = "Manager+, and only within the shift that took the money. Writes a "
                    + "negative reversal transaction with negated tenders, releases the blocks it "
                    + "paid for back to billable, puts the stock back with VOID movements and "
                    + "hands the loyalty back — one transaction, like the settle it undoes. The "
                    + "original row is never edited, only flagged with its reason.")
    public VoidView voidPayment(@PathVariable Long id, @Valid @RequestBody VoidRequest request) {
        return VoidView.of(payments.voidPayment(id, request.reason()));
    }

    /**
     * The two contract fields whose behaviour has not been built yet. Refusing beats accepting: a
     * settle that quietly dropped {@code tournamentEntries} would take the customer's money for a
     * registration no table has ever heard of.
     */
    private static void requireNotYetBuilt(String field, List<?> value, String when) {
        if (value != null && !value.isEmpty()) {
            throw ValidationFailedException.onField(field,
                    "%s is part of the contract but %s — send it then, not now".formatted(field, when));
        }
    }
}
