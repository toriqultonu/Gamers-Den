package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.MatchExecutionService;
import dev.gamersden.tournament.domain.TournamentEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * {@code GET /tournaments/{id}/matches} — the match board, and {@code ?pending=true} the cashier
 * job board (docs/tournaments.md §4).
 *
 * <p>The consoles ship with the matches on purpose. "Start" fails with 409 {@code NO_FREE_CONSOLE}
 * when every allocated seat is taken, and an operator who can see <em>which</em> seat is taken by
 * <em>what</em> can act on it — end a walk-in session, or wait for the match that is still on.
 * Splitting the two reads would let the board disagree with itself between them.
 */
@Schema(name = "MatchBoard")
public record MatchBoardView(
        @Schema(description = "Drawing order; with pending=true, only matches with both players "
                + "and no winner yet")
        List<TournamentMatchView> matches,
        @Schema(description = "Every console blocked for this event, in the order match start "
                + "picks from")
        List<ConsoleAvailabilityView> consoles,
        int freeConsoles) {

    public static MatchBoardView of(MatchExecutionService.Board board,
                                    List<TournamentEntry> entries) {
        return new MatchBoardView(TournamentMatchView.of(board.matches(), entries),
                ConsoleAvailabilityView.of(board.consoles()), board.freeConsoles());
    }
}
