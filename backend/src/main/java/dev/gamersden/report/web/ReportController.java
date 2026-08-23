package dev.gamersden.report.web;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.security.Roles;
import dev.gamersden.report.domain.ReportService;
import dev.gamersden.report.domain.ReportWindow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/**
 * {@code /reports} — S9 (design.md S9; ARCHITECTURE.md §4.3, the {@code report} package).
 *
 * <p>Manager+, per the permission matrix (api-contract.md §1: "Reports, Overview — Admin yes,
 * Manager reports only, Cashier no"). A cashier gets the 403 envelope; S9 also hides itself, but
 * that is cosmetic and this is the enforcement.
 *
 * <p>Read-only and un-keyed: no {@code Idempotency-Key}, because nothing is written. Ask twice and
 * the second answer may differ — that is the point of deriving rather than storing (§5.4).
 */
@RestController
@RequestMapping("/reports")
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reports;
    private final Clock clock;

    public ReportController(ReportService reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * Both bounds are venue days and both are inclusive. {@code to} defaults to today and
     * {@code from} to a fortnight back — the width of S9's trend chart — so an unqualified request
     * answers the screen's default view.
     */
    @GetMapping
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "KPIs, trends, utilisation, busiest hours, top sellers and bookings",
            description = "Every figure is folded from grouped reads at request time — nothing is "
                    + "stored, so a void or refund shows up immediately. Days are venue days "
                    + "(Asia/Dhaka), both bounds inclusive; the default range is the last 14 days. "
                    + "400 when to is before from or the range is longer than a year.")
    public ReportView report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        LocalDate last = to != null ? to : VenueTime.businessDay(clock);
        LocalDate first = from != null ? from : last.minusDays(ReportWindow.DEFAULT_DAYS - 1L);
        return ReportView.of(reports.report(new ReportWindow(first, last)));
    }
}
