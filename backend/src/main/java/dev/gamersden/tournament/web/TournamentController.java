package dev.gamersden.tournament.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.common.spi.TournamentEntrySale;
import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentEntryService;
import dev.gamersden.tournament.domain.TournamentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /tournaments} (api-contract.md, Tournaments; docs/tournaments.md §1).
 *
 * <p>The permission matrix splits cleanly in two and this class is where that split is
 * <em>enforced</em> — the sidebar hiding the tab is cosmetic, the 403 here is not:
 *
 * <ul>
 *   <li><strong>Configuration is Manager+</strong> — creating, editing, blocking consoles,
 *       cancelling. A cashier gets the 403 envelope.</li>
 *   <li><strong>Execution is everyone</strong> — reading the board and selling entries. A cashier
 *       running the counter has to be able to take a player's fee.</li>
 * </ul>
 *
 * <p>The bracket, the matches and the finance panel are B13/B14; nothing here pretends to have
 * them.
 */
@RestController
@RequestMapping("/tournaments")
@Tag(name = "Tournaments")
public class TournamentController {

    private final TournamentService tournaments;
    private final TournamentEntryService entries;
    private final TournamentEntrySale sales;

    public TournamentController(TournamentService tournaments, TournamentEntryService entries,
                                TournamentEntrySale sales) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.sales = sales;
    }

    // ---- reads --------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Events still selling or being played",
            description = "Soonest first. slotsLeft is what the POS Tournament category disables "
                    + "its card on.")
    public List<TournamentView> list() {
        return tournaments.upcoming().stream().map(this::view).toList();
    }

    @GetMapping("/history")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Finished and called-off events, most recent first")
    public List<TournamentView> history() {
        return tournaments.history().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One event with its entries and blocked consoles",
            description = "The bracket joins this payload in B13.")
    public TournamentDetailView get(@PathVariable Long id) {
        return detail(tournaments.get(id));
    }

    // ---- configuration (Manager+) ---------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Create an event (Manager+)",
            description = "maxPlayers must be one of 4, 8, 16 or 32 — a perfect bracket has "
                    + "exactly N-1 matches and no byes. 409 DUPLICATE_NAME on a taken name.")
    public TournamentDetailView create(@Valid @RequestBody CreateTournamentRequest request) {
        return detail(tournaments.create(request.name(), request.game(), request.cadence(),
                request.scheduledAt(), request.entryFee(), request.prizePool(),
                request.maxPlayers(), request.matchDurationMin()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Edit an event (Manager+)",
            description = "Only while OPEN — 409 TOURNAMENT_NOT_OPEN otherwise. Once a ticket has "
                    + "been sold the entry fee is frozen and the cap cannot drop below the "
                    + "entries taken; both are 409 CONFLICT.")
    public TournamentDetailView update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateTournamentRequest request) {
        return detail(tournaments.update(id, request.name(), request.game(), request.cadence(),
                request.scheduledAt(), request.entryFee(), request.prizePool(),
                request.maxPlayers(), request.matchDurationMin()));
    }

    @PutMapping("/{id}/blocks")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Hold consoles for an event (Manager+)",
            description = "Replaces the whole allocation. While the event is OPEN or LIVE these "
                    + "consoles read RESERVED on the Floor and refuse walk-in sessions with 409 "
                    + "STATION_RESERVED; an empty list releases them.")
    public TournamentDetailView setBlocks(@PathVariable Long id,
                                          @Valid @RequestBody StationBlocksRequest request) {
        tournaments.setStationBlocks(id, request.stationIds());
        return detail(tournaments.get(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Call an event off and refund everyone (Manager+)",
            description = "One transaction: status CANCELLED, every console released, and a "
                    + "negative refund transaction per originating sale, posted to the shift open "
                    + "on this terminal. Money goes back through the methods it came in by.")
    public CancellationView cancel(@PathVariable Long id,
                                   @Valid @RequestBody CancelTournamentRequest request) {
        TournamentService.Cancellation cancelled = tournaments.cancel(id, request.reason());
        return CancellationView.of(cancelled, entries.countOf(id));
    }

    // ---- selling (any role) ---------------------------------------------------------------------

    @PostMapping("/{id}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ANY_STAFF)
    @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key", required = true,
            description = "UUID. An identical retry replays the stored response with "
                    + "Idempotency-Replayed: true — the same entry, the same QR, one charge.")
    @Operation(summary = "Sell one entry at the counter",
            description = "The same settle POST /payments runs: one transaction writes the money, "
                    + "the entry with its seed and QR, and the receipt with its P5 stub. 409 "
                    + "TOURNAMENT_FULL past the cap, TOURNAMENT_NOT_OPEN once the bracket is live.")
    public EntrySoldView sellEntry(@PathVariable Long id, @Valid @RequestBody SellEntryRequest request) {
        List<TournamentEntrySale.TenderLine> tenders = request.splits().stream()
                .map(split -> new TournamentEntrySale.TenderLine(split.method(), split.amount(),
                        split.paymentRef()))
                .toList();
        return EntrySoldView.of(sales.sell(id, request.playerName(), tenders));
    }

    // ---- assembly -------------------------------------------------------------------------------

    private TournamentView view(Tournament tournament) {
        return TournamentView.of(tournament, entries.countOf(tournament.getId()));
    }

    private TournamentDetailView detail(Tournament tournament) {
        return TournamentDetailView.of(tournament, entries.of(tournament.getId()),
                tournaments.stationIdsOf(tournament.getId()));
    }
}
