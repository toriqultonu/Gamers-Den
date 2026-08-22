package dev.gamersden.tournament.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.tournament.domain.TournamentEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /tournament-entries/{id}/check-in} (api-contract.md, Tournaments; docs/tournaments.md §7)
 * — the door, not the office. Any role scans a ticket in.
 *
 * <p>Its own controller rather than a method on {@code TournamentController} because the path is
 * its own: the scanner has an entry id and a QR, not the event the entry belongs to.
 */
@RestController
@RequestMapping("/tournament-entries")
@Tag(name = "Tournaments")
public class TournamentEntryController {

    private final TournamentEntryService entries;

    public TournamentEntryController(TournamentEntryService entries) {
        this.entries = entries;
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Mark a player as arrived",
            description = "The QR off the P5 stub, which has to match the entry it is presented "
                    + "against. 409 ALREADY_CHECKED_IN on a second scan.")
    public TournamentEntryView checkIn(@PathVariable Long id, @Valid @RequestBody CheckInRequest request) {
        return TournamentEntryView.of(entries.checkIn(id, request.qrToken()));
    }
}
