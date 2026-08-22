package dev.gamersden.shift.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.ShiftReportPrinting.Kind;
import dev.gamersden.common.spi.ShiftTakingsLookup;
import dev.gamersden.shift.repo.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * X/Z computation (api-contract.md, "Shifts &amp; expenses"; TASKLIST B11).
 *
 * <p>Nothing here is stored. Expected cash is a derived value and derived values are never written
 * down (invariant §5.4) — the only exception is the close, which snapshots the figures onto the
 * shift row because a Z is evidence of a moment, and the moment does not survive the next sale.
 *
 * <p>The report is assembled from two reads and one pure function: the shift's postings from
 * {@code billing}, its petty cash from this package, and {@link ShiftTakings} to fold them into
 * the matrix. That split is deliberate — every rule about what counts as takings is arithmetic
 * that can be tested without a database, and this class is only the wiring.
 */
@Service
public class ShiftReportService {

    private final ShiftTakingsLookup takings;
    private final ExpenseRepository expenses;
    private final Clock clock;

    public ShiftReportService(ShiftTakingsLookup takings, ExpenseRepository expenses, Clock clock) {
        this.takings = takings;
        this.expenses = expenses;
        this.clock = clock;
    }

    /** {@code GET /shifts/current/x-report} — the interim read, drawer not counted. */
    @Transactional(readOnly = true)
    public ShiftReport interimReport(Shift shift) {
        return report(shift, Kind.X, null);
    }

    /** The Z's figures, computed inside the closing transaction so the snapshot matches the paper. */
    @Transactional(readOnly = true)
    public ShiftReport countedReport(Shift shift, int countedCash) {
        return report(shift, Kind.Z, countedCash);
    }

    private ShiftReport report(Shift shift, Kind kind, Integer countedCash) {
        ShiftTakings taken = ShiftTakings.of(takings.transactionsOf(shift.getId()),
                takings.tenderMethods());
        List<Expense> pettyCash = expenses.findByShiftIdOrderByIdAsc(shift.getId());
        ExpenseSummary spent = ExpenseSummary.of(pettyCash);
        CashCount cash = countedCash == null
                ? CashCount.interim(shift.getOpeningFloat(), taken.cash(), spent.total())
                : CashCount.counted(shift.getOpeningFloat(), taken.cash(), spent.total(), countedCash);
        return new ShiftReport(kind, shift.getId(), shift.getTerminal(), shift.getStaffId(),
                shift.getOpenedAt(), shift.getClosedAt(), VenueTime.now(clock),
                shift.getOpeningFloat(), taken, spent, cash, shift.getHandoverNote());
    }
}
