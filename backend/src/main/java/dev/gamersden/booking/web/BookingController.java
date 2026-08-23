package dev.gamersden.booking.web;

import dev.gamersden.booking.domain.BookingService;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * {@code /bookings} (api-contract.md, Pre-bookings; docs/bookings.md §2).
 *
 * <p>Every role works this page. §1's matrix ticks "Bookings: create (take payment), check-in +
 * token print, seat, cancel-with-refund (outside cutoff)" for Admin, Manager and Cashier alike —
 * taking money and letting customers in is the floor's job. Only the <em>settings</em> next door
 * are Admin, and only play-queue no-show removal (B16) is Manager+.
 *
 * <p>Create and cancel carry idempotency; check-in does not, and that asymmetry is the contract's
 * (§1). The two that move money are on the guarded route list, so {@code IdempotencyFilter}
 * handles their whole lifecycle around this controller — missing key → 400, retry → the stored
 * response with {@code Idempotency-Replayed: true}, same key with a different body → 409. Check-in
 * takes no money and is naturally idempotent through its own 409 {@code ALREADY_CHECKED_IN}: a
 * booking can only be checked in once.
 *
 * <p>One note on the contract's error list. {@code POST /bookings} is documented with 409
 * {@code SPLIT_MISMATCH}, but the body carries no amount: the server prices the slot from the rate
 * card and the package fee from {@code /booking-settings}, then tenders exactly what it priced, so
 * there is nothing for the two figures to disagree about. The code is left unthrown here rather
 * than manufactured — the tender refusals that <em>can</em> happen are {@code WALLET_INSUFFICIENT}
 * and {@code PAYMENT_REF_REQUIRED}, and they leave nothing written just the same.
 */
@RestController
@RequestMapping("/bookings")
@Tag(name = "Pre-bookings")
public class BookingController {

    private static final String UPCOMING = "upcoming";
    private static final String HISTORY = "history";

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The Upcoming or History tab",
            description = "upcoming = PAID, soonest first; history = ARRIVED, USED and CANCELLED, "
                    + "most recent slot first. Rows on the Upcoming tab carry overlapping=true "
                    + "when another live booking shares their console and their time.")
    public List<BookingView> list(@RequestParam(defaultValue = UPCOMING) String tab) {
        return switch (tab.toLowerCase(Locale.ROOT)) {
            case UPCOMING -> bookings.upcoming().stream().map(BookingView::of).toList();
            case HISTORY -> bookings.history().stream().map(BookingView::of).toList();
            default -> throw ValidationFailedException.onField("tab",
                    "tab is one of upcoming, history");
        };
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One booking")
    public BookingView get(@PathVariable Long id) {
        return BookingView.of(bookings.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
            description = "UUID. The first call is stored for 48 h; an identical retry replays it "
                    + "with Idempotency-Replayed: true and the same bookingId / transactionId / "
                    + "printJobId.")
    @Operation(summary = "Take payment and hold a slot",
            description = "One database transaction: the transaction snapshot with its "
                    + "booking_amount, the tender, the booking row and the print job carrying the "
                    + "P1 receipt and the P7 confirmation. The play total is blocks x the "
                    + "console's rate at the booked time and the package fee comes from "
                    + "/booking-settings; both are snapshotted onto the booking, so later edits to "
                    + "either reach new bookings only. 409 PREBOOKING_DISABLED when the feature is "
                    + "off, SPLIT_MISMATCH when the tender does not equal what is due, "
                    + "PAYMENT_REF_REQUIRED on a bKash/Nagad payment with no TrxID; each leaves "
                    + "nothing written. An overlap with another booking on the same console is "
                    + "returned as a warning, not refused.")
    public BookingCreatedView create(@Valid @RequestBody CreateBookingRequest request) {
        return BookingCreatedView.of(bookings.create(request.stationId(), request.memberId(),
                request.name(), request.phone(), request.startAt(), request.blocks(),
                request.method(), request.paymentRef()));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Check in and print the token",
            description = "Assigns the next daily queue token off the row-locked token_seq, writes "
                    + "its queue entry, moves the booking to ARRIVED and queues the P6 stub — one "
                    + "transaction. The token is shared with walk-up play tickets and restarts at "
                    + "venue midnight. Works while pre-booking is switched off: a booking already "
                    + "paid for stays serviceable. 409 ALREADY_CHECKED_IN on a second tap.")
    public CheckedInView checkIn(@PathVariable Long id) {
        return CheckedInView.of(bookings.checkIn(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Roles.ANY_STAFF)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
            description = "UUID. An identical retry replays the stored response, so the refund is "
                    + "written once.")
    @Operation(summary = "Cancel a booking and refund it in full",
            description = "Only while PAID and while now <= startAt - the booking's own "
                    + "cutoffHours snapshot; the boundary itself still cancels. Writes a full "
                    + "negative transaction against the sale, posted to the shift doing the "
                    + "cancelling. 409 CANCEL_CUTOFF_PASSED inside the window, ALREADY_CHECKED_IN "
                    + "once the customer has arrived — that money goes back through a Manager+ "
                    + "void of the transaction instead.")
    public BookingCancelledView cancel(@PathVariable Long id,
                                       @Valid @RequestBody(required = false)
                                       CancelBookingRequest request) {
        return BookingCancelledView.of(
                bookings.cancel(id, request == null ? null : request.reason()));
    }
}
