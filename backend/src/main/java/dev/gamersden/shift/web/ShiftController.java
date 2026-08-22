package dev.gamersden.shift.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.common.web.PageResponse;
import dev.gamersden.shift.domain.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /shifts} — S7 (api-contract.md, "Shifts &amp; expenses").
 *
 * <p>Opening and closing the till is every operator's job: "Expenses, shift open/close" is ticked
 * for all three roles in the permission matrix (§1), with one nuance the API enforces rather than
 * the UI — a cashier closes their own shift only, and a manager closes anyone's.
 *
 * <p>Nothing here takes an {@code Idempotency-Key}. None of these routes is on the contract's
 * guarded list (§1) and none needs to be: opening twice is already 409
 * {@code SHIFT_ALREADY_OPEN}, and closing twice is 409 because there is no longer a shift open to
 * close. The Z print job is created inside the closing transaction, so a retried close cannot
 * produce a second one either.
 */
@RestController
@RequestMapping("/shifts")
@Validated
@Tag(name = "Shifts & expenses")
public class ShiftController {

    private final ShiftService shifts;

    public ShiftController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Open a shift on this terminal",
            description = "409 SHIFT_ALREADY_OPEN when one is already open here — the terminal, "
                    + "not the operator, is what a shift is unique per. The float is what is in "
                    + "the drawer before the first sale; the close counts against it.")
    public ShiftView open(@Valid @RequestBody OpenShiftRequest request) {
        return ShiftView.of(shifts.open(request.openingFloat()));
    }

    @GetMapping("/current/x-report")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The interim read of this terminal's open shift",
            description = "Takings by method x category — gaming, F&B, tournament entries and "
                    + "pre-bookings — the shift's petty cash, and the cash the drawer should hold "
                    + "right now. Nothing is stored: ask again after the next sale and the "
                    + "figures will have moved. print=true also queues the P3 job.")
    public ShiftReportView xReport(@RequestParam(defaultValue = "false") boolean print) {
        ShiftService.PrintedReport printed = shifts.interimReport(print);
        return ShiftReportView.of(printed.report(), printed.printJobId());
    }

    @PostMapping("/current/close")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Count the drawer and close the shift",
            description = "One transaction: the Z figures snapshotted onto the shift, the P2 print "
                    + "job, an alert row when the count does not match, and the operator signed "
                    + "out of this terminal. A cashier may only close their own shift; Manager+ "
                    + "may close anyone's.")
    public ShiftReportView close(@Valid @RequestBody CloseShiftRequest request) {
        ShiftService.PrintedReport closed =
                shifts.close(request.countedCash(), request.handoverNote());
        return ShiftReportView.of(closed.report(), closed.printJobId());
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Shift history",
            description = "Newest first, open shift included. The drawer figures are the Z "
                    + "snapshots and are absent on a shift that is still open.")
    public PageResponse<ShiftView> history(@RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.of(shifts.history(pageable), ShiftView::of);
    }
}
