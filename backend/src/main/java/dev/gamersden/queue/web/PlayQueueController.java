package dev.gamersden.queue.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.queue.domain.PlayQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /play-queue} and {@code /play-tickets} (api-contract.md, "Play queue";
 * docs/bookings.md §3).
 *
 * <p>Selling a ticket and seating a token are every operator's job — §1's matrix ticks "Play
 * tickets: sell, seat from queue, add time" for Admin, Manager and Cashier alike. Handing money
 * back is not: removing a no-show writes a refund, so it is Manager+, and the API is what enforces
 * that rather than the UI.
 *
 * <p>{@code POST /play-tickets} is on the guarded route list (§1), so
 * {@code IdempotencyFilter} handles its whole lifecycle around this controller — missing key →
 * 400, retry → the stored response with {@code Idempotency-Replayed: true}, same key with a
 * different body → 409. Seating and removal are deliberately not on that list: both are naturally
 * idempotent through their own 409, because a token can only be spent once.
 */
@RestController
@Tag(name = "Play queue")
@RequestMapping
public class PlayQueueController {

    private final PlayQueueService queue;

    public PlayQueueController(PlayQueueService queue) {
        this.queue = queue;
    }

    @GetMapping("/play-queue")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Who plays next",
            description = "Every WAITING token in counter order, then today's SEATED ones as "
                    + "history. Waiting tokens are not filtered to today: one issued yesterday and "
                    + "never seated keeps working and keeps its place, carrying its own tokenDate "
                    + "— the entry id is the key, not the number, which restarts at venue midnight.")
    public List<QueueEntryView> rail() {
        return queue.rail().stream().map(QueueEntryView::of).toList();
    }

    @PostMapping("/play-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
            description = "UUID. The first call is stored for 48 h; an identical retry replays it "
                    + "with Idempotency-Replayed: true and the same token / transactionId / "
                    + "printJobId — no second number comes off the daily counter.")
    @Operation(summary = "Sell one prepaid play-queue token",
            description = "The standalone alias for POST /payments playTickets[]. One database "
                    + "transaction: the transaction snapshot with its booking_amount, the tender, "
                    + "the queue entry holding the next daily token, and the print job carrying "
                    + "the P1 receipt and the P6 stub. Sellable while every console is busy — that "
                    + "is what the queue is for. 409 PAYMENT_REF_REQUIRED on a bKash/Nagad sale "
                    + "with no TrxID; 400 on a console type the rate card does not know.")
    public PlayTicketSoldView sell(@Valid @RequestBody SellPlayTicketRequest request) {
        return PlayTicketSoldView.of(queue.sell(request.consoleType(), request.blocks(),
                request.playerName(), request.method(), request.paymentRef()));
    }

    @PostMapping("/play-queue/{id}/seat")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Seat a waiting token on a console",
            description = "One database transaction: the session, its prepaid session_blocks born "
                    + "carrying the original sale's paid_tx_id, the token to SEATED, and — when "
                    + "the token came from a pre-booking — the booking to USED. The clock starts "
                    + "when staff press start, and extra time is ordinary billable +30 blocks. "
                    + "Any waiting token may be seated, not just the first: the customer chooses. "
                    + "409 CONSOLE_TYPE_MISMATCH on the wrong console type, STATION_BUSY on a "
                    + "taken seat, STATION_RESERVED while a tournament holds it.")
    public SeatedView seat(@PathVariable Long id, @Valid @RequestBody SeatRequest request) {
        return SeatedView.of(queue.seat(id, request.stationId()));
    }

    @DeleteMapping("/play-queue/{id}")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Refund and remove a no-show",
            description = "Manager+. One database transaction: a negative transaction against the "
                    + "sale, for the amount the token was sold at, and the token flipped to "
                    + "REFUNDED. The row is kept — the refund hangs off it — and the rail simply "
                    + "stops listing it. Walk-up tickets only: a checked-in booking's token is "
                    + "refunded by voiding its transaction, which revokes the token with it. 409 "
                    + "CONFLICT on a token already seated or already refunded.")
    public QueueEntryRemovedView remove(@PathVariable Long id,
                                        @RequestParam(required = false) String reason) {
        return QueueEntryRemovedView.of(queue.remove(id, reason));
    }
}
