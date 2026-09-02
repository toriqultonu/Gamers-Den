package dev.gamersden.shift.domain;

import dev.gamersden.auth.domain.StaffRole;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ForbiddenException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.AlertPublisher;
import dev.gamersden.common.spi.OperatorSignOut;
import dev.gamersden.common.spi.ShiftReportPrinting;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.common.util.Money;
import dev.gamersden.shift.repo.ShiftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Opening and closing the till (api-contract.md, "Shifts &amp; expenses"): {@code POST /shifts},
 * {@code POST /shifts/current/close}, {@code GET /shifts}.
 *
 * <h2>One open shift per terminal</h2>
 *
 * <p>Everything that takes money asks this package which shift to post to
 * ({@code ShiftLookupService}), so "the open shift on T1" has to be a single row or reconciliation
 * stops meaning anything. It is guarded twice: with the 409 {@code SHIFT_ALREADY_OPEN} the
 * contract names, and underneath it by {@code one_open_shift_per_terminal}, the partial unique
 * index in V001 — which is what actually holds when two tills open at the same instant, and is
 * translated back into the same 409 rather than surfacing as a 500.
 *
 * <h2>Closing is a snapshot, not a calculation to be repeated</h2>
 *
 * <p>Expected cash is derived and derived values are never stored (invariant §5.4) — except at
 * close, where {@code expected_cash} and {@code discrepancy} are written onto the shift, because a
 * Z is evidence about a moment that the next sale would make unrecomputable. The close writes
 * them, the Z print job, the discrepancy alert and the operator's sign-out in one transaction: a
 * shift cannot end up closed without its report, and a rolled-back close cannot leave paper, an
 * alert or a signed-out cashier behind.
 */
@Service
public class ShiftService {

    private static final Logger log = LoggerFactory.getLogger(ShiftService.class);

    private final ShiftRepository shifts;
    private final ShiftReportService reports;
    private final ShiftReportPrinting printing;
    private final AlertPublisher alerts;
    private final OperatorSignOut signOut;
    private final SyncOutboxWriter outbox;
    private final Clock clock;

    public ShiftService(ShiftRepository shifts,
                        ShiftReportService reports,
                        ShiftReportPrinting printing,
                        AlertPublisher alerts,
                        OperatorSignOut signOut,
                        SyncOutboxWriter outbox,
                        Clock clock) {
        this.shifts = shifts;
        this.reports = reports;
        this.printing = printing;
        this.alerts = alerts;
        this.signOut = signOut;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ---- POST /shifts ------------------------------------------------------------------------

    /** Opens the caller's terminal for business. 409 {@code SHIFT_ALREADY_OPEN} if it already is. */
    @Transactional
    public Shift open(int openingFloat) {
        StaffPrincipal staff = CurrentStaff.require();
        if (openingFloat < 0) {
            throw ValidationFailedException.onField("openingFloat",
                    "An opening float cannot be negative");
        }
        shifts.findByTerminalAndClosedAtIsNull(staff.terminal()).ifPresent(open -> {
            throw alreadyOpen(staff.terminal(), open);
        });
        Shift shift;
        try {
            shift = shifts.saveAndFlush(new Shift(staff.id(), staff.terminal(), openingFloat));
        } catch (DataIntegrityViolationException race) {
            // one_open_shift_per_terminal fired: another till won by microseconds (V001).
            throw shifts.findByTerminalAndClosedAtIsNull(staff.terminal())
                    .map(open -> alreadyOpen(staff.terminal(), open))
                    .orElseThrow(() -> race);
        }
        log.info("shift {} opened on {} by staff {} with a float of {}",
                shift.getId(), shift.getTerminal(), shift.getStaffId(),
                Money.format(shift.getOpeningFloat()));
        // Every transaction carries a shift_id, so the cloud cannot reconcile a takings row it has
        // no shift for — the drawer's own lifecycle is part of the money path (invariant §5.7).
        outbox.record(SyncOutboxWriter.SHIFTS, SyncOutboxWriter.OPENED, shift.getId(),
                SyncOutboxWriter.data("terminal", shift.getTerminal(),
                        "staffId", shift.getStaffId(),
                        "openingFloat", shift.getOpeningFloat()));
        return shift;
    }

    // ---- GET /shifts/current/x-report ---------------------------------------------------------

    /** The terminal's open shift, or 409 — every money route needs one before it can post. */
    @Transactional(readOnly = true)
    public Shift current(String terminal) {
        return shifts.findByTerminalAndClosedAtIsNull(terminal)
                .orElseThrow(() -> noShiftOpen(terminal));
    }

    /**
     * {@code GET /shifts/current/x-report}. A pure read unless {@code print} is asked for, in
     * which case the P3 job is rendered and queued in the same transaction that computed the
     * figures — the paper can never disagree with the response that produced it (invariant §5.5).
     */
    @Transactional
    public PrintedReport interimReport(boolean print) {
        StaffPrincipal staff = CurrentStaff.require();
        ShiftReport report = reports.interimReport(current(staff.terminal()));
        Long printJobId = print
                ? printing.issueShiftReport(report.asDocument(staff.terminal(), staff.id()))
                : null;
        return new PrintedReport(report, printJobId);
    }

    // ---- POST /shifts/current/close -----------------------------------------------------------

    /**
     * Counts the drawer and closes the shift: the Z snapshot onto the row, the P2 job, an alert
     * when the count does not match, and the operator signed out of the till — one transaction.
     *
     * <p>A cashier may only close their own shift (api-contract.md §1 — "shift open/close ✓ (own
     * shift)"); Manager+ may close anyone's, which is what a handover with the cashier already
     * gone looks like.
     */
    @Transactional
    public PrintedReport close(int countedCash, String handoverNote) {
        StaffPrincipal staff = CurrentStaff.require();
        if (countedCash < 0) {
            throw ValidationFailedException.onField("countedCash", "A drawer count cannot be negative");
        }
        Shift shift = shifts.findOpenByTerminalForUpdate(staff.terminal())
                .orElseThrow(() -> noShiftOpen(staff.terminal()));
        requireMayClose(staff, shift);

        OffsetDateTime at = VenueTime.now(clock);
        shift.setClosedAt(at);
        shift.setCountedCash(countedCash);
        shift.setHandoverNote(handoverNote == null || handoverNote.isBlank()
                ? null : handoverNote.trim());

        ShiftReport report = reports.countedReport(shift, countedCash);
        shift.setExpectedCash(report.cash().expected());
        shift.setDiscrepancy(report.cash().discrepancy());

        long printJobId = printing.issueShiftReport(report.asDocument(staff.terminal(), staff.id()));
        if (report.cash().discrepancy() != 0) {
            raiseDiscrepancyAlert(shift, report);
        }
        signOut.signOutOfTerminal(shift.getStaffId(), shift.getTerminal());
        outbox.record(SyncOutboxWriter.SHIFTS, SyncOutboxWriter.CLOSED, shift.getId(),
                SyncOutboxWriter.data("terminal", shift.getTerminal(),
                        "staffId", shift.getStaffId(),
                        "closedBy", staff.id(),
                        "openingFloat", shift.getOpeningFloat(),
                        "expectedCash", report.cash().expected(),
                        "countedCash", countedCash,
                        "discrepancy", report.cash().discrepancy(),
                        "takings", report.takings().totals().total(),
                        "tournamentTakings", report.takings().totals().tournament(),
                        "bookingTakings", report.takings().totals().booking(),
                        "expenses", report.expenses().total(),
                        "printJobId", printJobId));

        log.info("shift {} closed on {} by staff {} — expected {}, counted {}, discrepancy {}; "
                        + "Z print job {}",
                shift.getId(), shift.getTerminal(), staff.id(),
                Money.format(report.cash().expected()), Money.format(countedCash),
                Money.format(report.cash().discrepancy()), printJobId);
        return new PrintedReport(report, printJobId);
    }

    /**
     * The discrepancy alert the contract asks for ("discrepancy ≠ 0 writes alert"). Raised inside
     * the close, so the feed can never claim a shift is short that is in fact still open.
     */
    private void raiseDiscrepancyAlert(Shift shift, ShiftReport report) {
        int discrepancy = report.cash().discrepancy();
        long alertId = alerts.raise(AlertPublisher.CASH_DISCREPANCY,
                "Drawer %s on %s".formatted(discrepancy > 0 ? "over" : "short", shift.getTerminal()),
                "Shift #%d closed by staff %d: expected %s, counted %s (%s%s)."
                        .formatted(shift.getId(), shift.getStaffId(),
                                Money.format(report.cash().expected()),
                                Money.format(report.cash().counted()),
                                discrepancy > 0 ? "+" : "", Money.format(discrepancy)));
        log.warn("shift {} closed with a discrepancy of {} — alert {}",
                shift.getId(), Money.format(discrepancy), alertId);
    }

    // ---- GET /shifts --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Shift> history(Pageable pageable) {
        return shifts.findAll(pageable);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static void requireMayClose(StaffPrincipal staff, Shift shift) {
        if (staff.role() == StaffRole.CASHIER && !shift.getStaffId().equals(staff.id())) {
            throw new ForbiddenException("Shift #%d belongs to another operator — a manager closes it"
                    .formatted(shift.getId()));
        }
    }

    private static ConflictException alreadyOpen(String terminal, Shift open) {
        return new ConflictException(ErrorCode.SHIFT_ALREADY_OPEN,
                "Shift #%d is already open on %s — close it before opening another"
                        .formatted(open.getId(), terminal),
                Map.of("terminal", terminal, "shiftId", open.getId(), "staffId", open.getStaffId()));
    }

    private static ConflictException noShiftOpen(String terminal) {
        return new ConflictException(ErrorCode.CONFLICT,
                "No shift is open on %s".formatted(terminal), Map.of("terminal", terminal));
    }

    /** A report and the job that printed it, when one was asked for. */
    public record PrintedReport(ShiftReport report, Long printJobId) {
    }
}
