package dev.gamersden.report.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.report.domain.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /overview} — S2 (design.md S2; ARCHITECTURE.md §4.3, the {@code report} package).
 *
 * <p>Admin only. The permission matrix gives managers reports but not the Overview
 * (api-contract.md §1), and design.md S2 says a non-admin who lands here is redirected to the
 * Floor — this endpoint is what makes that more than a redirect.
 *
 * <p>Takes no parameters: the windows are fixed by the screen (today, the last 30 days, and the 30
 * before them for the comparison line) and all three are venue days.
 */
@RestController
@RequestMapping("/overview")
@Tag(name = "Reports")
public class OverviewController {

    private final OverviewService overview;

    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Today's KPIs, the pre-sold stat, trends, stock watchlist and closes",
            description = "Occupancy is this instant; the KPI tiles are the venue day; the trends "
                    + "are the last 30 venue days against the 30 before them. Pre-sold is money "
                    + "taken for play not yet delivered — PAID bookings plus WAITING play "
                    + "tickets. Nothing is stored; everything is folded per request.")
    public OverviewView overview() {
        return OverviewView.of(overview.overview());
    }
}
