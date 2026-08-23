package dev.gamersden.session.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.CartLookup;
import dev.gamersden.common.spi.PrepaidSeatLookup;
import dev.gamersden.common.spi.PrepaidSeatLookup.PrepaidSeat;
import dev.gamersden.common.spi.SessionSeating;
import dev.gamersden.common.spi.ShiftLookup;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.StationReservation;
import dev.gamersden.session.repo.SessionBlockRepository;
import dev.gamersden.session.repo.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The floor state machine (api-contract.md, Sessions): {@code OPEN -> RUNNING <-> PAUSED ->
 * LOCKED -> CLOSED}, the plus/minus 30-minute block ledger underneath it, and the clock.
 *
 * <p>Three rules shape every method here:
 *
 * <ol>
 *   <li><strong>Server time only</strong> (invariant §5.1). Elapsed time is measured between
 *       {@code running_since} and the {@link Clock} bean; a request never carries a duration.</li>
 *   <li><strong>Prices are snapshots.</strong> A block records what it cost the moment it was
 *       bought, morning window included, so a {@code PUT /pricing} mid-session moves the next
 *       block and never a sold one.</li>
 *   <li><strong>Prepaid blocks are born paid</strong> (invariant §5.9). Blocks loaded from a
 *       booking or a play ticket carry the sale's {@code paid_tx_id}, which is what lets the seat
 *       end at a net outstanding of zero without a second payment.</li>
 * </ol>
 *
 * <p>Cross-package reads all go through {@code common.spi} doors — the station and its rate card,
 * the open shift, the unsettled cart, the prepaid token — never a foreign repository (§3).
 */
@Service
public class SessionService implements SessionSeating {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessions;
    private final SessionBlockRepository blocks;
    private final StationLookup stations;
    private final StationReservation reservations;
    private final PrepaidSeatLookup prepaidSeats;
    private final CartLookup carts;
    private final ShiftLookup shifts;
    private final Clock clock;

    public SessionService(SessionRepository sessions,
                          SessionBlockRepository blocks,
                          StationLookup stations,
                          StationReservation reservations,
                          PrepaidSeatLookup prepaidSeats,
                          CartLookup carts,
                          ShiftLookup shifts,
                          Clock clock) {
        this.sessions = sessions;
        this.blocks = blocks;
        this.stations = stations;
        this.reservations = reservations;
        this.prepaidSeats = prepaidSeats;
        this.carts = carts;
        this.shifts = shifts;
        this.clock = clock;
    }

    // ---- reads ----------------------------------------------------------------------------

    /**
     * Not {@code readOnly}: a read is also when the server notices a countdown reached zero and
     * persists the RUNNING to LOCKED flip. The value returned is derived either way.
     */
    @Transactional
    public SessionDetail detail(long id) {
        Session session = sessions.findById(id).orElseThrow(() -> new NotFoundException("Session", id));
        return settleAndDescribe(session, VenueTime.now(clock));
    }

    /** {@code GET /sessions?active=true} — live seats; {@code active=false} lists closed ones. */
    @Transactional
    public List<SessionDetail> list(boolean active) {
        OffsetDateTime at = VenueTime.now(clock);
        List<Session> found = active
                ? sessions.findByStateNot(SessionState.CLOSED)
                : sessions.findByStateOrderByEndedAtDesc(SessionState.CLOSED);
        return found.stream().map(session -> settleAndDescribe(session, at)).toList();
    }

    // ---- POST /sessions -------------------------------------------------------------------

    /**
     * Fills a seat. Walk-in when both token ids are null; otherwise the prepaid blocks behind the
     * booking or queue entry are loaded as already paid and the token is consumed, all in this one
     * transaction (invariant §5.9).
     *
     * <p>409 {@code STATION_BUSY} when the seat is taken, {@code STATION_RESERVED} when a
     * tournament holds it, {@code CONSOLE_TYPE_MISMATCH} when a token is carried to the wrong
     * console.
     */
    @Transactional
    public SessionDetail open(Long stationId, Long memberId, Long bookingId, Long queueEntryId) {
        OffsetDateTime at = VenueTime.now(clock);
        StationLookup.StationInfo station = stations.find(stationId)
                .orElseThrow(() -> new NotFoundException("Station", stationId));
        requireSeatAvailable(station, at);

        PrepaidSeat seat = resolvePrepaidSeat(bookingId, queueEntryId);
        if (seat != null && !seat.consoleType().equals(station.consoleType())) {
            throw new ConflictException(ErrorCode.CONSOLE_TYPE_MISMATCH,
                    "This token is for a %s — %s is a %s"
                            .formatted(seat.consoleType(), station.name(), station.consoleType()),
                    Map.of("stationId", station.id(), "expected", seat.consoleType(),
                            "actual", station.consoleType()));
        }

        Session session = new Session(station.id(), requireOpenShift());
        session.setMemberId(memberId != null ? memberId : (seat == null ? null : seat.memberId()));
        if (seat != null) {
            session.setQueueEntryId(seat.queueEntryId());
        }
        persist(session, station);

        if (seat != null) {
            for (int i = 0; i < seat.blocks(); i++) {
                blocks.save(new SessionBlock(session.getId(), seat.blockPrice(), seat.paidTxId()));
            }
            prepaidSeats.consume(seat, session.getId());
            log.info("session {} opened on station {} from token {} with {} prepaid blocks (tx {})",
                    session.getId(), station.id(), seat.queueEntryId(), seat.blocks(), seat.paidTxId());
        } else {
            log.info("session {} opened on station {}", session.getId(), station.id());
        }
        return settleAndDescribe(session, at);
    }

    /**
     * {@code POST /play-queue/{id}/seat} — the same seat, started from the token's side rather
     * than the station's (api-contract.md, "Play queue").
     *
     * <p>Deliberately a delegation and nothing more: the Floor's queue rail and
     * {@code POST /sessions} with a {@code queueEntryId} are one act, so they run one piece of
     * code and share every guard — 409 {@code STATION_BUSY}, {@code STATION_RESERVED},
     * {@code CONSOLE_TYPE_MISMATCH} — and the one transaction of invariant §5.9.
     */
    @Override
    @Transactional
    public SeatedSession seat(long stationId, long queueEntryId) {
        SessionDetail seated = open(stationId, null, null, queueEntryId);
        return new SeatedSession(seated.session().getId(), seated.session().getStationId(),
                queueEntryId, seated.state().name(), seated.blockCount(), seated.paidBlocks(),
                seated.remainingSeconds(), seated.balance().netOutstanding());
    }

    // ---- POST /sessions/{id}/blocks --------------------------------------------------------

    /**
     * {@code {delta: +/-1}} — one 30-minute block on or off. Idempotent through the
     * {@code Idempotency-Key} filter, so a retried tap can never sell the same half hour twice.
     *
     * <p>Removal is refused with 409 {@code BLOCKS_CONSUMED} once the block has been paid for or
     * played: the ledger only ever gives back time nobody has used and nobody has bought.
     */
    @Transactional
    public SessionDetail changeBlocks(long id, int delta) {
        if (delta != 1 && delta != -1) {
            throw ValidationFailedException.onField("delta", "delta must be +1 or -1");
        }
        OffsetDateTime at = VenueTime.now(clock);
        Session session = liveSession(id, "buy or return time");
        List<SessionBlock> live = liveBlocks(id);

        if (delta > 0) {
            int price = stations.blockPriceAt(session.getStationId(), at);
            blocks.save(new SessionBlock(id, price, null));
            if (session.getState() == SessionState.LOCKED) {
                // Time bought on a locked seat unlocks it, clock stopped until staff resume.
                move(session, SessionState.PAUSED);
            }
            log.info("session {} bought a block at {} ({} -> {} blocks)",
                    id, price, live.size(), live.size() + 1);
        } else {
            removeOneBlock(session, live, at);
        }
        return settleAndDescribe(session, at);
    }

    /**
     * Gives back the newest block that is neither paid for nor played. Both refusals are 409
     * {@code BLOCKS_CONSUMED} — from the operator's side "that time is gone" is one situation.
     */
    private void removeOneBlock(Session session, List<SessionBlock> live, OffsetDateTime at) {
        long consumed = SessionClock.consumedSeconds(session, at);
        if (SessionClock.purchasedSeconds(live.size() - 1) < consumed) {
            throw new ConflictException(ErrorCode.BLOCKS_CONSUMED,
                    "That half hour has already been played",
                    Map.of("sessionId", session.getId(), "blocks", live.size(),
                            "consumedSeconds", consumed));
        }
        SessionBlock removable = live.reversed().stream()
                .filter(block -> block.getPaidTxId() == null)
                .findFirst()
                .orElseThrow(() -> new ConflictException(ErrorCode.BLOCKS_CONSUMED,
                        live.isEmpty()
                                ? "This session has no blocks to return"
                                : "Every block on this session is already paid for",
                        Map.of("sessionId", session.getId(), "blocks", live.size())));
        removable.setRemoved(true);
        log.info("session {} returned block {} ({} -> {} blocks)",
                session.getId(), removable.getId(), live.size(), live.size() - 1);
    }

    // ---- POST /sessions/{id}/clock ---------------------------------------------------------

    /**
     * {@code START | PAUSE | RESUME}. Each action is legal from exactly one state (see
     * {@link ClockAction}); anywhere else is 409 {@code CONFLICT}, and starting or resuming
     * without time left is 409 {@code NO_BLOCKS}.
     */
    @Transactional
    public SessionDetail clock(long id, ClockAction action) {
        OffsetDateTime at = VenueTime.now(clock);
        Session session = liveSession(id, "run the clock");
        List<SessionBlock> live = liveBlocks(id);
        settleExpiry(session, live.size(), at);
        requireState(session, action);

        switch (action) {
            case START, RESUME -> {
                if (SessionClock.remainingSeconds(session, live.size(), at) <= 0) {
                    throw new ConflictException(ErrorCode.NO_BLOCKS,
                            live.isEmpty()
                                    ? "Buy at least one 30-minute block before starting the clock"
                                    : "No time left on this session — add a block first",
                            Map.of("sessionId", id, "blocks", live.size()));
                }
                move(session, action.to());
                session.setRunningSince(at);
            }
            case PAUSE -> {
                session.setConsumedSec(SessionClock.bankedSecondsOnStop(session, live.size(), at));
                session.setRunningSince(null);
                move(session, action.to());
            }
        }
        log.info("session {} clock {} -> {} ({}s consumed)", id, action, session.getState(),
                SessionClock.consumedSeconds(session, at));
        return settleAndDescribe(session, at);
    }

    // ---- POST /sessions/{id}/end -----------------------------------------------------------

    /**
     * Ends the session, but only at a net outstanding of zero (invariant §5.9): unpaid blocks plus
     * the unsettled cart. Prepaid blocks carry a {@code paid_tx_id} and so count as settled, which
     * is how a booking or play-ticket seat closes without paying twice. Anything still owed is 409
     * {@code SESSION_HAS_BALANCE} with the breakdown in {@code details}.
     */
    @Transactional
    public SessionDetail end(long id) {
        OffsetDateTime at = VenueTime.now(clock);
        Session session = liveSession(id, "end");
        List<SessionBlock> live = liveBlocks(id);
        SessionBalance balance = SessionBalance.of(live, carts.unsettledTotal(id));
        if (!balance.isSettled()) {
            throw new ConflictException(ErrorCode.SESSION_HAS_BALANCE,
                    "This session still owes %d BDT — settle the bill before ending it"
                            .formatted(balance.netOutstanding()),
                    Map.of("sessionId", id,
                            "netOutstanding", balance.netOutstanding(),
                            "gamingDue", balance.gamingDue(),
                            "fnbDue", balance.fnbDue()));
        }
        session.setConsumedSec(SessionClock.bankedSecondsOnStop(session, live.size(), at));
        session.setRunningSince(null);
        move(session, SessionState.CLOSED);
        session.setEndedAt(at);
        log.info("session {} ended on station {} after {}s", id, session.getStationId(),
                session.getConsumedSec());
        return SessionDetail.of(session, live, balance, at);
    }

    // ---- expiry ----------------------------------------------------------------------------

    /**
     * Persists the LOCKED flip for every session whose purchased time has run out. Reads already
     * derive it (see {@link SessionClock#effectiveState}), so nothing depends on this having run —
     * it only keeps the stored state honest on an idle floor.
     */
    @Transactional
    public int lockExhaustedSessions() {
        OffsetDateTime at = VenueTime.now(clock);
        int locked = 0;
        for (Session session : sessions.findByStateIn(List.of(SessionState.RUNNING, SessionState.PAUSED))) {
            if (settleExpiry(session, liveBlocks(session.getId()).size(), at)) {
                locked++;
            }
        }
        return locked;
    }

    /**
     * The one place RUNNING or PAUSED becomes LOCKED. Banks the time played (capped at the time
     * bought, so an overrun never records minutes nobody paid for) and stops the clock.
     *
     * @return true when this call moved the session to LOCKED
     */
    private boolean settleExpiry(Session session, int blockCount, OffsetDateTime at) {
        if (!session.getState().canRunOutOfTime() || !SessionClock.isOutOfTime(session, blockCount, at)) {
            return false;
        }
        session.setConsumedSec(SessionClock.bankedSecondsOnStop(session, blockCount, at));
        session.setRunningSince(null);
        move(session, SessionState.LOCKED);
        log.info("session {} locked — purchased time exhausted after {}s",
                session.getId(), session.getConsumedSec());
        return true;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private SessionDetail settleAndDescribe(Session session, OffsetDateTime at) {
        List<SessionBlock> live = liveBlocks(session.getId());
        settleExpiry(session, live.size(), at);
        return SessionDetail.of(session, live,
                SessionBalance.of(live, carts.unsettledTotal(session.getId())), at);
    }

    private List<SessionBlock> liveBlocks(Long sessionId) {
        return blocks.findBySessionIdAndRemovedFalseOrderByIdAsc(sessionId);
    }

    private Session liveSession(long id, String what) {
        Session session = sessions.findById(id).orElseThrow(() -> new NotFoundException("Session", id));
        if (!session.getState().isLive()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Session %d is already closed — cannot %s".formatted(id, what),
                    Map.of("sessionId", id, "state", session.getState().name()));
        }
        return session;
    }

    private void requireSeatAvailable(StationLookup.StationInfo station, OffsetDateTime at) {
        sessions.findByStationIdAndStateNot(station.id(), SessionState.CLOSED).ifPresent(live -> {
            throw stationBusy(station, live.getId());
        });
        if (reservations.isReserved(station.id(), at)) {
            throw new ConflictException(ErrorCode.STATION_RESERVED,
                    "%s is held by a tournament".formatted(station.name()),
                    Map.of("stationId", station.id()));
        }
        if (station.underMaintenance()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s is under maintenance".formatted(station.name()),
                    Map.of("stationId", station.id()));
        }
    }

    /**
     * The flush is deliberate: {@code one_live_session_per_station} is the real arbiter of the
     * one-live-session rule, and two operators seating the same console at the same instant must
     * lose at the index, not only at the check above. The loser gets the same 409 either way.
     */
    private void persist(Session session, StationLookup.StationInfo station) {
        try {
            sessions.saveAndFlush(session);
        } catch (DataIntegrityViolationException ex) {
            throw stationBusy(station, null);
        }
    }

    private static ConflictException stationBusy(StationLookup.StationInfo station, Long sessionId) {
        Map<String, Object> details = sessionId == null
                ? Map.of("stationId", station.id())
                : Map.of("stationId", station.id(), "sessionId", sessionId);
        return new ConflictException(ErrorCode.STATION_BUSY,
                "%s already has a live session".formatted(station.name()), details);
    }

    /** At most one token id, and it has to resolve to a paid seat waiting to be filled. */
    private PrepaidSeat resolvePrepaidSeat(Long bookingId, Long queueEntryId) {
        if (bookingId != null && queueEntryId != null) {
            throw ValidationFailedException.onField("bookingId",
                    "pass either bookingId or queueEntryId, not both");
        }
        if (bookingId != null) {
            return prepaidSeats.findByBooking(bookingId)
                    .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        }
        if (queueEntryId != null) {
            return prepaidSeats.findByQueueEntry(queueEntryId)
                    .orElseThrow(() -> new NotFoundException("Queue entry", queueEntryId));
        }
        return null;
    }

    /** Every session belongs to a shift — that is how its transactions reconcile later (§5.7). */
    private long requireOpenShift() {
        StaffPrincipal staff = CurrentStaff.require();
        return shifts.openShiftId(staff.terminal())
                .orElseThrow(() -> new ConflictException(ErrorCode.CONFLICT,
                        "No shift is open on %s — open one before seating a customer"
                                .formatted(staff.terminal()),
                        Map.of("terminal", staff.terminal())));
    }

    private void requireState(Session session, ClockAction action) {
        if (session.getState() != action.from()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Cannot %s a %s session".formatted(action.name().toLowerCase(),
                            session.getState().name()),
                    Map.of("sessionId", session.getId(), "state", session.getState().name(),
                            "action", action.name()));
        }
    }

    /** Guards the transition table itself: an illegal move is a bug, not a client error. */
    private static void move(Session session, SessionState next) {
        if (!session.getState().canMoveTo(next)) {
            throw new IllegalStateException("Illegal session transition %s -> %s on session %d"
                    .formatted(session.getState(), next, session.getId()));
        }
        session.setState(next);
    }
}
