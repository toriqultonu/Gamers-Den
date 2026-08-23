package dev.gamersden.printing.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.printing.domain.PrintJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /print-jobs} (api-contract.md, "Print jobs") — the endpoints behind S11, the print
 * preview (design.md §5).
 *
 * <p>Every operator prints: §1's matrix reads "Sessions, POS, payments, prints ✓ ✓ ✓". The one
 * capability that is not everyone's is reprinting <em>someone else's</em> ticket — "Void/reprint
 * others' transactions ✓ ✓ ✗" — and because that depends on who queued the job rather than on
 * which route was called, it is checked in the service against the job's operator rather than by
 * a role annotation here.
 *
 * <p>What is deliberately absent: {@code POST /print-jobs}. TASKLIST B18 scopes this task to job
 * status, render, retry, reprint, the printers list and the test ticket; every artifact the venue
 * prints already gets its job created inside the transaction that produced it (invariant §5.3),
 * so there is nothing for a bare create to do that {@code reprint} does not do with a reason
 * attached.
 */
@RestController
@Tag(name = "Print jobs")
@RequestMapping("/print-jobs")
public class PrintJobController {

    private final PrintJobService jobs;

    public PrintJobController(PrintJobService jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One ticket's queue state",
            description = "QUEUED, PRINTING, DONE or FAILED, with the attempt count, the device, "
                    + "the operator, and — on a reprint — its reason and the original job. A "
                    + "FAILED job carries which failure it was (PAPER_OUT, COVER_OPEN, OFFLINE, "
                    + "MID_PRINT), so S11 names the thing to fix.")
    public PrintJobView get(@PathVariable Long id) {
        return PrintJobView.of(jobs.require(id));
    }

    @GetMapping("/{id}/render")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The stored 48-column render",
            description = "The preview S11 draws, read back from the job — never recomputed. It "
                    + "was produced by the same pass that produced the bytes on the paper, so what "
                    + "is on screen is what came out of the printer.")
    public PrintRenderView render(@PathVariable Long id) {
        return PrintRenderView.of(jobs.require(id));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Re-queue a failed ticket, same bytes",
            description = "The stored bytes go back to the printer unchanged — not a fresh render "
                    + "— so the reprinted ticket is byte-identical to the one that failed, "
                    + "including after a mid-print failure where half of it is already on paper. "
                    + "The attempt count keeps climbing rather than resetting. 409 CONFLICT on a "
                    + "job that is not FAILED: a QUEUED job is already going to print, and a DONE "
                    + "one needs a reprint reason.")
    public PrintJobView retry(@PathVariable Long id) {
        return PrintJobView.of(jobs.retry(id));
    }

    @PostMapping("/{id}/reprint")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Print it again, on the record",
            description = "A new job carrying the original's stored bytes under the reprint band, "
                    + "with the reason recorded and the original linked. The reason is required — "
                    + "400 VALIDATION_FAILED without it. Reprinting another operator's ticket "
                    + "needs Manager+ (api-contract.md §1, \"Void/reprint others' transactions\").")
    public PrintJobView reprint(@PathVariable Long id, @Valid @RequestBody ReprintRequest request) {
        return PrintJobView.of(jobs.reprint(id, request.reason()));
    }
}
