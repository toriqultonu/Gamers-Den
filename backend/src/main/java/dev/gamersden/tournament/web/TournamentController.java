package dev.gamersden.tournament.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.common.spi.TournamentEntrySale;
import dev.gamersden.tournament.domain.BracketService;
import dev.gamersden.tournament.domain.MatchExecutionService;
import dev.gamersden.tournament.domain.Tournament;
import dev.gamersden.tournament.domain.TournamentEntry;
import dev.gamersden.tournament.domain.TournamentEntryService;
import dev.gamersden.tournament.domain.TournamentFinanceService;
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
import org.springframework.web.bind.annotation.RequestParam;
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
 *       cancelling, and reading the finance panel. A cashier gets the 403 envelope.</li>
 *   <li><strong>Execution is everyone</strong> — reading the board, selling entries, starting
 *       matches and adding time to them. A cashier running the counter has to be able to take a
 *       player's fee and put the next match on a console.</li>
 * </ul>
 *
 * <p>The bracket splits the same way. Drawing one is configuration — Manager+ — and so is
 * deciding a match nobody started; recording the result of a match that <em>is</em> being played
 * is execution, and the route lets any role through so the guard can look at the match instead of
 * the URL (docs/tournaments.md §1, §4).
 *
 * <p>Finance is the one read with a role guard of its own (§6): the numbers are never folded into
 * a shared payload, so there is no route by which a cashier could see them.
 */
@RestController
@RequestMapping("/tournaments")
@Tag(name = "Tournaments")
public class TournamentController {

    private final TournamentService tournaments;
    private final TournamentEntryService entries;
    private final BracketService brackets;
    private final MatchExecutionService matches;
    private final TournamentFinanceService finance;
    private final TournamentEntrySale sales;

    public TournamentController(TournamentService tournaments, TournamentEntryService entries,
                                BracketService brackets, MatchExecutionService matches,
                                TournamentFinanceService finance, TournamentEntrySale sales) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.brackets = brackets;
        this.matches = matches;
        this.finance = finance;
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
    @Operation(summary = "Finished and called-off events, most recent first",
            description = "The History tab: winners, prizes and entry counts by date. A finished "
                    + "event carries winnerEntryId and winnerName; a called-off one carries its "
                    + "cancelledReason instead.")
    public List<TournamentView> history() {
        return tournaments.history().stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One event with its entries, blocked consoles and bracket",
            description = "bracket is empty until the event is drawn — before that the screen is "
                    + "the registered-player list. Every started match carries its own "
                    + "remainingSeconds, computed from the server clock.")
    public TournamentDetailView get(@PathVariable Long id) {
        return detail(tournaments.get(id));
    }

    @GetMapping("/{id}/matches")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The match board, with console availability",
            description = "pending=true narrows it to the cashier job board: matches with both "
                    + "players and no winner yet. The consoles come with it, each carrying why it "
                    + "is or is not free — \"Allocated console busy with a walk-in session\" is "
                    + "the case start would otherwise refuse without explanation.")
    public MatchBoardView board(@PathVariable Long id,
                                @RequestParam(defaultValue = "false") boolean pending) {
        return MatchBoardView.of(matches.board(id, pending), entries.of(id));
    }

    @GetMapping("/{id}/finance")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Revenue against what the consoles would have earned (Manager+)",
            description = "403 for a cashier token, and never embedded in a shared payload. "
                    + "revenue = entries x entryFee; netProfit = revenue - prizePool; "
                    + "opportunityCost = (N-1) x matchDurationMin/60 x avgHourlyRate of the "
                    + "allocated consoles; extraMargin = netProfit - opportunityCost.")
    public TournamentFinanceView finance(@PathVariable Long id) {
        return TournamentFinanceView.of(finance.of(id));
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

    // ---- the bracket ------------------------------------------------------------------------------

    @PostMapping("/{id}/bracket")
    @PreAuthorize(Roles.MANAGER_PLUS)
    @Operation(summary = "Draw the bracket now (Manager+)",
            description = "For an event that never filled: the smallest power-of-two bracket that "
                    + "seats everybody who bought in, byes advancing the earliest seeds, and the "
                    + "event goes LIVE. An event that fills is drawn automatically by the sale "
                    + "that takes the last slot, so this is the undersubscribed case. 409 "
                    + "NOT_ENOUGH_PLAYERS under two players, 409 TOURNAMENT_NOT_OPEN once it is "
                    + "already live, done or called off.")
    public TournamentDetailView generateBracket(@PathVariable Long id) {
        brackets.generate(id);
        return detail(tournaments.get(id));
    }

    // ---- match execution (any role) ---------------------------------------------------------------

    @PostMapping("/{id}/matches/{mid}/start")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Put a match on a console",
            description = "Takes the first allocated console that is neither hosting an unfinished "
                    + "match nor busy with a walk-in session, and stamps started_at — the "
                    + "countdown runs from there. 409 NO_FREE_CONSOLE when every allocated console "
                    + "is taken; the details list what each of them is doing.")
    public TournamentMatchView start(@PathVariable Long id, @PathVariable Long mid) {
        return TournamentMatchView.of(matches.start(id, mid), this.namesOf(id));
    }

    @PostMapping("/{id}/matches/{mid}/extend")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Add time to a match in play",
            description = "Minutes accumulate on the match; every countdown re-bases off the same "
                    + "read, so the board, the bracket tag, the \"Now on\" tile and the Floor "
                    + "card all move together. A match whose time is already up is the normal "
                    + "case. 409 CONFLICT on a match that has not been started or is already "
                    + "decided.")
    public TournamentMatchView extend(@PathVariable Long id, @PathVariable Long mid,
                                      @Valid @RequestBody ExtendMatchRequest request) {
        return TournamentMatchView.of(matches.extend(id, mid, request.minutes()), this.namesOf(id));
    }

    @PostMapping("/{id}/matches/{mid}/winner")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "Record the winner of a match",
            description = "Any role for a match that has been started — that is execution. A match "
                    + "nobody started is a ruling and needs Manager+: a cashier gets the 403 "
                    + "envelope. The winner advances along next_match_id, and the response says "
                    + "which console their next match would take. Winning the final makes the "
                    + "champion, turns the event DONE and releases every console it held.")
    public MatchDecisionView recordWinner(@PathVariable Long id, @PathVariable Long mid,
                                          @Valid @RequestBody RecordWinnerRequest request) {
        BracketService.Decision decision = brackets.recordWinner(id, mid, request.winnerEntryId());
        Long suggested = decision.champion() ? null
                : matches.suggestConsole(id).orElse(null);
        return MatchDecisionView.of(detail(decision.tournament()),
                decision.next() == null ? null : decision.next().getId(), suggested,
                decision.champion());
    }

    // ---- assembly -------------------------------------------------------------------------------

    private TournamentView view(Tournament tournament) {
        return TournamentView.of(tournament, entries.countOf(tournament.getId()),
                entries.playerNameOf(tournament.getWinnerEntryId()));
    }

    private TournamentDetailView detail(Tournament tournament) {
        List<TournamentEntry> sold = entries.of(tournament.getId());
        return TournamentDetailView.of(tournament, sold,
                tournaments.stationIdsOf(tournament.getId()),
                matches.bracketOf(tournament.getId(), tournament.getMatchDurationMin()));
    }

    /** Player names for one event, so a single-match response can label its two sides. */
    private java.util.function.Function<Long, String> namesOf(Long tournamentId) {
        List<TournamentEntry> sold = entries.of(tournamentId);
        return entryId -> sold.stream()
                .filter(entry -> entry.getId().equals(entryId))
                .map(TournamentEntry::getPlayerName)
                .findFirst().orElse(null);
    }
}
