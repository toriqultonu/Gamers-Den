package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.BillService;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /sessions/{id}/bill} — the path hangs off {@code /sessions}, the endpoint belongs to
 * {@code billing} (ARCHITECTURE.md §4.3). Every operator may quote a bill: it is the read half of
 * "Sessions, POS, payments, prints" in the permission matrix.
 *
 * <p>No {@code Idempotency-Key} here — the bill is a pure read. The write it feeds,
 * {@code POST /payments}, is the idempotent one (B10).
 */
@RestController
@RequestMapping("/sessions")
@Tag(name = "Billing")
public class BillController {

    private final BillService bills;

    public BillController(BillService bills) {
        this.bills = bills;
    }

    @GetMapping("/{id}/bill")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "What this session owes right now",
            description = "Gaming counts unbilled blocks only at their snapshot rates — prepaid "
                    + "blocks and blocks settled mid-session are already paid for and show as "
                    + "prepaidCredit instead of a charge. F&B comes from the unsettled cart, "
                    + "tournament entry fees from the session's registrations, and "
                    + "pointsRedeemable is min(member points, netTotal). Nothing here is stored.")
    public BillView bill(@PathVariable Long id) {
        return BillView.of(bills.of(id));
    }
}
