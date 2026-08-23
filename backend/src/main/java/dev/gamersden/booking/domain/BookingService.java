package dev.gamersden.booking.domain;

import dev.gamersden.booking.repo.BookingRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.BookingSale;
import dev.gamersden.common.spi.MemberBookingLookup;
import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.QueueTokenIssuing;
import dev.gamersden.common.spi.QueueTokenLookup;
import dev.gamersden.common.spi.SaleRefunding;
import dev.gamersden.common.spi.StationLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The booking lifecycle of docs/bookings.md §2 — create, check in, cancel, and the two tabs that
 * list them.
 *
 * <pre>
 * PAID --check-in--&gt; ARRIVED --seat (Floor, B16)--&gt; USED
 *   \--cancel (&gt;= cutoff h before start)--&gt; CANCELLED (full refund)
 * </pre>
 *
 * <p>Four rules shape everything here.
 *
 * <p><strong>Pay first, in one transaction.</strong> A booking is a sale before it is a row:
 * {@link BookingSale} writes the transaction, its tenders and the print job, and
 * {@code BookingRegistrationService} writes the booking inside it (invariant §5.3). Nothing on
 * this class can create a booking any other way, and {@code bookings.tx_id} is {@code NOT NULL},
 * so an unpaid booking is not a state the system can reach.
 *
 * <p><strong>The snapshots are the contract.</strong> The block rate, the package fee and the
 * cutoff window are read once, at sale, and copied onto the row. Every later decision — what a
 * cancel hands back, when the cancel window closes — reads the booking, never the current settings
 * or the current rate card, so an Admin editing either cannot reach a customer who has already
 * paid (invariant §5.11).
 *
 * <p><strong>The feature flag guards the door, not the building.</strong> {@code enabled=false}
 * refuses <em>new</em> bookings with 409 {@code PREBOOKING_DISABLED}; check-in and cancel never
 * ask, so the bookings already sold stay serviceable (docs/bookings.md §7).
 *
 * <p><strong>Check-in is money-shaped even though it takes no money.</strong> The token
 * allocation, the queue entry and the P6 stub are one transaction (invariant §5.3): a check-in
 * that rolls back cannot burn a token number or leave paper promising a place in a queue nobody is
 * in.
 */
@Service
public class BookingService implements MemberBookingLookup {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    /** How much of the bookings strip the member detail shows (api-contract.md, members). */
    public static final int RECENT_BOOKINGS = 10;

    /** {@code queue_entries.source} for a token issued by a booking check-in. */
    private static final String SOURCE_BOOKING = "BOOKING";

    private final BookingRepository bookings;
    private final BookingSettingsService settings;
    private final StationLookup stations;
    private final MemberPointsLookup members;
    private final BookingSale sales;
    private final SaleRefunding refunds;
    private final QueueTokenIssuing tokens;
    private final QueueTokenLookup issuedTokens;
    private final PlayTicketPrinting tickets;
    private final Clock clock;

    public BookingService(BookingRepository bookings,
                          BookingSettingsService settings,
                          StationLookup stations,
                          MemberPointsLookup members,
                          BookingSale sales,
                          SaleRefunding refunds,
                          QueueTokenIssuing tokens,
                          QueueTokenLookup issuedTokens,
                          PlayTicketPrinting tickets,
                          Clock clock) {
        this.bookings = bookings;
        this.settings = settings;
        this.stations = stations;
        this.members = members;
        this.sales = sales;
        this.refunds = refunds;
        this.tokens = tokens;
        this.issuedTokens = issuedTokens;
        this.tickets = tickets;
        this.clock = clock;
    }

    // ---- POST /bookings -----------------------------------------------------------------------

    /**
     * {@code POST /bookings} — pay first, hold the slot (docs/bookings.md §2).
     *
     * <p>The price is quoted for {@code startAt}, not for now: the morning discount belongs to the
     * time that will be played, so a slot booked at 18:00 for tomorrow at 11:00 is sold at the
     * morning rate. It is then a snapshot like every other — a later {@code PUT /pricing} moves
     * new bookings only, exactly as {@code session_blocks.price} does (invariant §5.11).
     *
     * <p>An overlap with another booking on the same console is a <em>warning</em>, never a
     * refusal (docs/bookings.md §7): the floor seats the token on any free console of the same
     * type, so refusing would only teach staff to book the wrong seat. The clashing ids come back
     * on the response and the Upcoming list flags them.
     */
    @Transactional
    public Created create(long stationId, Long memberId, String name, String phone,
                          OffsetDateTime startAt, int blocks, String method, String paymentRef) {
        BookingSettings current = settings.requireEnabled();
        StationLookup.StationInfo station = stations.find(stationId)
                .orElseThrow(() -> new NotFoundException("Station", stationId));
        OffsetDateTime slot = requireFutureSlot(startAt);
        int blockPrice = stations.blockPriceAt(stationId, slot);

        BookingSale.Order order = new BookingSale.Order(station.id(), station.name(),
                station.consoleType(), memberId, nameFor(name, memberId), trimmedOrNull(phone),
                slot, blocks, blocks * blockPrice, current.getPackageFee(),
                current.getCancelCutoffHours(), method, paymentRef);
        BookingSale.SoldBooking sold = sales.sell(order);

        Booking booking = bookings.findById(sold.bookingId())
                .orElseThrow(() -> new IllegalStateException(
                        "booking %d vanished inside its own transaction".formatted(sold.bookingId())));
        return new Created(
                summaryOf(booking, station, null, !sold.overlappingBookingIds().isEmpty()),
                sold.transactionId(), sold.publicId(), sold.printJobId(),
                sold.overlappingBookingIds());
    }

    // ---- POST /bookings/{id}/check-in ---------------------------------------------------------

    /**
     * {@code POST /bookings/{id}/check-in} — "Check in and print token" (docs/bookings.md §2). Any
     * role: this is the door, not the office.
     *
     * <p>One transaction: the next daily token off the row-locked {@code token_seq}, the
     * {@code queue_entries} row that owns it, the booking flipped to ARRIVED and the P6 stub, all
     * or nothing (invariants §5.3, §5.10). The booking row is locked for the duration, so two
     * terminals checking the same customer in cannot both get a token — the second finds ARRIVED
     * and answers 409 {@code ALREADY_CHECKED_IN}.
     *
     * <p>The booked console being busy is deliberately not checked. Check-in issues a token; the
     * seat is chosen later from the Floor, on this console or another of the same type
     * (docs/bookings.md §7). The feature flag is deliberately not read either: a booking already
     * paid for stays serviceable after pre-booking is switched off.
     */
    @Transactional
    public CheckedIn checkIn(long bookingId) {
        StaffPrincipal staff = CurrentStaff.require();
        Booking booking = lock(bookingId);
        requireCheckInable(booking);
        StationLookup.StationInfo station = stations.find(booking.getStationId())
                .orElseThrow(() -> new NotFoundException("Station", booking.getStationId()));

        QueueTokenIssuing.IssuedToken token = tokens.issue(new QueueTokenIssuing.TokenRequest(
                SOURCE_BOOKING, booking.getId(), booking.getTxId(), booking.getName(),
                station.consoleType(), booking.getBlocks()));
        booking.setQueueEntryId(token.queueEntryId());
        booking.setStatus(BookingStatus.ARRIVED);

        long printJobId = tickets.issuePlayTicket(new PlayTicketPrinting.PlayTicket(
                token.queueEntryId(), token.tokenNo(), token.tokenDate(), booking.getName(),
                station.consoleType(), booking.getBlocks(), true, station.name(),
                booking.getStartAt(), staff.terminal(), staff.id(), VenueTime.now(clock)));

        log.info("booking {} checked in by staff {} — TOKEN #{} of {} on queue entry {}, "
                        + "print job {}", booking.getId(), staff.id(), token.tokenNo(),
                token.tokenDate(), token.queueEntryId(), printJobId);
        return new CheckedIn(summaryOf(booking, station, token.tokenNo(), false), token, printJobId);
    }

    // ---- POST /bookings/{id}/cancel -----------------------------------------------------------

    /**
     * {@code POST /bookings/{id}/cancel} — called off outside the window, money back in full
     * (docs/bookings.md §2).
     *
     * <p>Three refusals, and they are not the same 409. Past the cutoff is
     * {@code CANCEL_CUTOFF_PASSED} — the venue has held a console it can no longer resell. After
     * the customer has arrived it is {@code ALREADY_CHECKED_IN}: the way to give that money back
     * is a Manager+ void of the transaction, not a cancel (docs/bookings.md §7). A booking already
     * cancelled is a plain {@code CONFLICT}, because there is nothing left to hand back.
     *
     * <p>The cutoff is measured against the booking's own {@code cutoff_hours} snapshot and the
     * server clock, and the boundary itself is inside the window: at exactly
     * {@code start_at − cutoff_hours} the cancel still goes through (invariants §5.1, §5.11).
     */
    @Transactional
    public Cancelled cancel(long bookingId, String reason) {
        Booking booking = lock(bookingId);
        requireCancellable(booking);

        String why = reason == null || reason.isBlank()
                ? "Booking #%d cancelled".formatted(booking.getId())
                : reason.trim();
        SaleRefunding.Refund refund = booking.total() > 0
                ? refunds.refund(new SaleRefunding.RefundRequest(booking.getTxId(), booking.total(),
                        SaleRefunding.Bucket.BOOKING, why))
                : null;
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setRefundTxId(refund == null ? null : refund.transactionId());

        log.info("booking {} cancelled ({}) — {} BDT returned on {}", booking.getId(), why,
                booking.total(), refund == null ? "nothing (it was sold for 0)" : refund.publicId());
        return new Cancelled(summaryOf(booking, station(booking.getStationId()), null, false), refund);
    }

    // ---- GET /bookings ------------------------------------------------------------------------

    /** {@code tab=upcoming} — everything still owed, soonest first (docs/bookings.md §2). */
    @Transactional(readOnly = true)
    public List<BookingSummary> upcoming() {
        return summarise(bookings.findByStatusOrderByStartAtAsc(BookingStatus.PAID), true);
    }

    /** {@code tab=history} — arrived, seated and called off, most recent slot first. */
    @Transactional(readOnly = true)
    public List<BookingSummary> history() {
        return summarise(bookings.findByStatusInOrderByStartAtDesc(
                List.of(BookingStatus.ARRIVED, BookingStatus.USED, BookingStatus.CANCELLED)), false);
    }

    @Transactional(readOnly = true)
    public BookingSummary get(long id) {
        Booking booking = bookings.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking", id));
        return summarise(List.of(booking), false).get(0);
    }

    /** The member card's bookings strip — the {@code booking} half of {@code GET /members/{id}}. */
    @Override
    @Transactional(readOnly = true)
    public List<MemberBooking> recentBookings(long memberId, int limit) {
        List<Booking> rows = bookings.findByMemberIdOrderByStartAtDesc(memberId).stream()
                .limit(limit)
                .toList();
        return summarise(rows, false).stream()
                .map(row -> new MemberBooking(row.booking().getId(), row.booking().getStationId(),
                        row.stationName(), row.booking().getStartAt(), row.booking().getBlocks(),
                        row.booking().total(), row.booking().getStatus().name(), row.tokenNo()))
                .toList();
    }

    // ---- assembly -----------------------------------------------------------------------------

    /**
     * Names the consoles, joins the tokens and re-runs the overlap warning over the list — two
     * reads for the whole page rather than two per row, and none of it stored (invariant §5.4).
     *
     * <p>The overlap check only looks inside the list it was given, which is exactly right for the
     * Upcoming tab: two live bookings clash with each other, and a slot that clashes with one
     * already played or called off is not a clash anybody has to act on.
     */
    private List<BookingSummary> summarise(List<Booking> rows, boolean flagOverlaps) {
        Map<Long, StationLookup.StationInfo> byStation = new HashMap<>();
        rows.forEach(booking -> byStation.computeIfAbsent(booking.getStationId(), this::station));
        Map<Long, QueueTokenLookup.Token> byToken = issuedTokens.tokensOf(rows.stream()
                .map(Booking::getQueueEntryId)
                .filter(Objects::nonNull)
                .toList());

        List<BookingSummary> summaries = new ArrayList<>(rows.size());
        for (Booking booking : rows) {
            QueueTokenLookup.Token token = booking.getQueueEntryId() == null
                    ? null
                    : byToken.get(booking.getQueueEntryId());
            boolean clash = flagOverlaps && rows.stream()
                    .anyMatch(other -> !other.getId().equals(booking.getId())
                            && booking.overlaps(other));
            summaries.add(new BookingSummary(booking, nameOf(booking, byStation),
                    consoleOf(booking, byStation), token == null ? null : token.tokenNo(),
                    token == null ? null : token.tokenDate(), clash,
                    booking.cancellableAt(VenueTime.now(clock))));
        }
        return List.copyOf(summaries);
    }

    private BookingSummary summaryOf(Booking booking, StationLookup.StationInfo station,
                                     Integer tokenNo, boolean overlapping) {
        return new BookingSummary(booking,
                station == null ? "Station " + booking.getStationId() : station.name(),
                station == null ? null : station.consoleType(),
                tokenNo,
                tokenNo == null ? null : VenueTime.businessDay(clock),
                overlapping,
                booking.cancellableAt(VenueTime.now(clock)));
    }

    private static String nameOf(Booking booking, Map<Long, StationLookup.StationInfo> byStation) {
        StationLookup.StationInfo station = byStation.get(booking.getStationId());
        return station == null ? "Station " + booking.getStationId() : station.name();
    }

    private static String consoleOf(Booking booking, Map<Long, StationLookup.StationInfo> byStation) {
        StationLookup.StationInfo station = byStation.get(booking.getStationId());
        return station == null ? null : station.consoleType();
    }

    private StationLookup.StationInfo station(Long stationId) {
        return stations.find(stationId).orElse(null);
    }

    // ---- guards -------------------------------------------------------------------------------

    private Booking lock(long bookingId) {
        return bookings.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }

    private static void requireCheckInable(Booking booking) {
        if (booking.getStatus().hasArrived()) {
            throw new ConflictException(ErrorCode.ALREADY_CHECKED_IN,
                    "%s has already checked in".formatted(booking.getName()),
                    Map.of("bookingId", booking.getId(), "status", booking.getStatus().name()));
        }
        if (!booking.getStatus().isOpen()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Booking %d is %s — there is nobody to check in"
                            .formatted(booking.getId(), booking.getStatus()),
                    Map.of("bookingId", booking.getId(), "status", booking.getStatus().name()));
        }
    }

    private void requireCancellable(Booking booking) {
        if (booking.getStatus().hasArrived()) {
            throw new ConflictException(ErrorCode.ALREADY_CHECKED_IN,
                    "%s has already checked in — void the transaction to hand the money back"
                            .formatted(booking.getName()),
                    Map.of("bookingId", booking.getId(), "status", booking.getStatus().name(),
                            "transactionId", booking.getTxId()));
        }
        if (!booking.getStatus().isOpen()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Booking %d is already cancelled".formatted(booking.getId()),
                    Map.of("bookingId", booking.getId(), "status", booking.getStatus().name()));
        }
        OffsetDateTime now = VenueTime.now(clock);
        if (now.isAfter(booking.cancellableUntil())) {
            throw new ConflictException(ErrorCode.CANCEL_CUTOFF_PASSED,
                    "Booking %d locks %d hour(s) before it starts — cancellation closed at %s"
                            .formatted(booking.getId(), booking.getCutoffHours(),
                                    booking.cancellableUntil()),
                    Map.of("bookingId", booking.getId(),
                            "cutoffHours", booking.getCutoffHours(),
                            "cancellableUntil", booking.cancellableUntil().toString(),
                            "startAt", booking.getStartAt().toString()));
        }
    }

    /**
     * A slot in the past cannot be honoured, and a booking is the promise of a slot. 400 rather
     * than a silent accept: the operator has mistyped the date, and the customer is standing there
     * to be asked.
     */
    private OffsetDateTime requireFutureSlot(OffsetDateTime startAt) {
        OffsetDateTime now = VenueTime.now(clock);
        if (!startAt.isAfter(now)) {
            throw ValidationFailedException.onField("startAt",
                    "A booking has to be for a slot that has not started yet");
        }
        return startAt.withOffsetSameInstant(now.getOffset());
    }

    /**
     * Free-text name, or the attached member's. The contract makes {@code name} required, so this
     * only fills in for a member attached with the box left blank — the customer is on file, and
     * their name is a better answer than a validation error.
     */
    private String nameFor(String name, Long memberId) {
        String trimmed = trimmedOrNull(name);
        if (trimmed != null) {
            return trimmed;
        }
        return Optional.ofNullable(memberId)
                .flatMap(members::loyaltyOf)
                .map(MemberPointsLookup.Loyalty::name)
                .orElseThrow(() -> ValidationFailedException.onField("name",
                        "A booking needs a name, or a member to take one from"));
    }

    private static String trimmedOrNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    // ---- results ------------------------------------------------------------------------------

    /** @param overlappingBookingIds live bookings this one runs into — a warning, not a refusal */
    public record Created(BookingSummary booking,
                          long transactionId,
                          String publicId,
                          long printJobId,
                          List<Long> overlappingBookingIds) {
    }

    public record CheckedIn(BookingSummary booking,
                            QueueTokenIssuing.IssuedToken token,
                            long printJobId) {
    }

    /** @param refund {@code null} only when the booking was sold for nothing at all */
    public record Cancelled(BookingSummary booking, SaleRefunding.Refund refund) {
    }
}
