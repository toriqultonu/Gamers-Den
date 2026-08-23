package dev.gamersden.printing.web;

import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.Roles;
import dev.gamersden.printing.domain.PrintJobService;
import dev.gamersden.printing.domain.PrinterDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /printers} (api-contract.md, "Print jobs") — the device half of S11.
 *
 * <p>Reading the list is every operator's business: it is how the counter knows whether the
 * printer is out of paper before a customer is standing in front of it. Choosing the venue's
 * printer is not — that is terminal configuration, and §1's matrix puts "terminal settings write"
 * with Admin. The test ticket sits with the readers, because printing is what all three roles do.
 *
 * <p>The test ticket goes through the queue like every other job: claimed, attempted, DONE or
 * FAILED. A test that wrote straight to the port would prove the printer works while proving
 * nothing about the path a receipt takes, which is the only thing worth testing at 9 a.m.
 */
@RestController
@Tag(name = "Printers")
@RequestMapping("/printers")
public class PrinterController {

    private final PrinterDirectory printers;
    private final PrintJobService jobs;

    public PrinterController(PrinterDirectory printers, PrintJobService jobs) {
        this.printers = printers;
        this.jobs = jobs;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Attached printers and their live status",
            description = "Default first. Each row's status is polled from the device while "
                    + "answering — ONLINE, OFFLINE, OUT_OF_PAPER or COVER_OPEN — rather than read "
                    + "from a cache, because the answer is only useful if it is current.")
    public List<PrinterView> list() {
        return printers.list().stream().map(PrinterView::of).toList();
    }

    @PutMapping("/default")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Choose the printer the venue prints on",
            description = "Admin, as terminal configuration is (api-contract.md §1). The id must "
                    + "be one GET /printers listed — 404 otherwise. The choice holds for this "
                    + "running process; the venue's standing default lives in configuration "
                    + "(gamersden.printing.default-device), because the printer model is still an "
                    + "OPEN FLAG and no schema document gives printers a table.")
    public PrinterView setDefault(@Valid @RequestBody DefaultPrinterRequest request) {
        return PrinterView.of(printers.chooseDefault(request.printerId()));
    }

    @PostMapping("/{printerId}/test")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Queue a test ticket on this printer",
            description = "An ordinary print job — it queues, gets claimed, is attempted up to "
                    + "three times and ends DONE or FAILED like any receipt, so what it proves is "
                    + "the whole path and not just the cable. 404 on an id nothing answers to.")
    public PrintJobView test(@PathVariable String printerId) {
        PrinterDirectory.Printer printer = printers.require(printerId);
        return PrintJobView.of(jobs.queueTestTicket(CurrentStaff.require().terminal(), printer));
    }
}
