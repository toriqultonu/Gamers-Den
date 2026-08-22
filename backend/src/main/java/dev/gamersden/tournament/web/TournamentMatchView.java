package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.MatchExecutionService;
import dev.gamersden.tournament.domain.TournamentEntry;
import dev.gamersden.tournament.domain.TournamentMatch;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One node of the bracket (design.md S12: bracket columns, Round of 16 → Final).
 *
 * <p>Player names ride along with the ids so a bracket column can be drawn without joining back
 * into {@code entries} on the client. A {@code null} side is either a bracket position nobody
 * bought — a bye, marked as such — or a slot still waiting for the round below to finish.
 *
 * <p>{@code remainingSeconds} is the one clock every surface ticks from: the bracket tag
 * ({@code console · mm:ss}), the "Now on «console»" tile, the match board row and the Floor card
 * all read this number and a server-time offset, never a local wall clock (docs/tournaments.md §4,
 * invariant §5.1). It is {@code null} on a match nobody has started and on one already decided —
 * there is no countdown for either — and {@code 0} with {@code timeUp} set once the allotted time
 * has run out, which is the row that reads "time up — record the winner".
 */
@Schema(name = "TournamentMatch")
public record TournamentMatchView(Long id,
                                  @Schema(description = "1 = first round") int round,
                                  @Schema(description = "Position in the round, from 1") int slot,
                                  Long entryA,
                                  String playerA,
                                  Long entryB,
                                  String playerB,
                                  Long winnerEntryId,
                                  String winnerName,
                                  @Schema(description = "The match this winner advances into; "
                                          + "null on the final")
                                  Long nextMatchId,
                                  @Schema(description = "One player, one empty bracket position — "
                                          + "decided by the draw, never played")
                                  boolean bye,
                                  Long stationId,
                                  String stationName,
                                  OffsetDateTime startedAt,
                                  int extraMinutes,
                                  @Schema(description = "(matchDurationMin + extraMinutes) x 60 - "
                                          + "elapsed, floored at 0; null unless the match is on")
                                  Long remainingSeconds,
                                  @Schema(description = "The countdown has hit zero — the match is "
                                          + "over and the winner still has to be recorded")
                                  boolean timeUp,
                                  Long decidedBy,
                                  OffsetDateTime decidedAt) {

    public static List<TournamentMatchView> of(List<MatchExecutionService.LiveMatchView> bracket,
                                               List<TournamentEntry> entries) {
        Map<Long, String> names = entries.stream()
                .collect(java.util.stream.Collectors.toMap(TournamentEntry::getId,
                        TournamentEntry::getPlayerName, (first, second) -> first));
        return bracket.stream().map(live -> of(live, names::get)).toList();
    }

    public static TournamentMatchView of(MatchExecutionService.LiveMatchView live,
                                         Function<Long, String> names) {
        TournamentMatch match = live.match();
        return new TournamentMatchView(match.getId(), match.getRound(), match.getSlot(),
                match.getEntryA(), name(names, match.getEntryA()),
                match.getEntryB(), name(names, match.getEntryB()),
                match.getWinnerEntry(), name(names, match.getWinnerEntry()),
                match.getNextMatchId(), match.isBye(), match.getStationId(), live.stationName(),
                match.getStartedAt(), match.getExtraMin(), live.remainingSeconds(), live.timeUp(),
                match.getDecidedBy(), match.getDecidedAt());
    }

    private static String name(Function<Long, String> names, Long entryId) {
        return entryId == null ? null : names.apply(entryId);
    }
}
