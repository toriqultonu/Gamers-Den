package dev.gamersden.tournament.web;

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
 * <p>{@code stationId}, {@code startedAt} and {@code extraMinutes} are the execution columns; B13
 * only ever writes results, so they read as whatever match start and {@code /extend} (B14) have
 * left there. The live {@code remainingSeconds} countdown lands with those endpoints.
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
                                  OffsetDateTime startedAt,
                                  int extraMinutes,
                                  Long decidedBy,
                                  OffsetDateTime decidedAt) {

    public static List<TournamentMatchView> of(List<TournamentMatch> bracket,
                                               List<TournamentEntry> entries) {
        Map<Long, String> names = entries.stream()
                .collect(java.util.stream.Collectors.toMap(TournamentEntry::getId,
                        TournamentEntry::getPlayerName, (first, second) -> first));
        return bracket.stream().map(match -> of(match, names::get)).toList();
    }

    public static TournamentMatchView of(TournamentMatch match, Function<Long, String> names) {
        return new TournamentMatchView(match.getId(), match.getRound(), match.getSlot(),
                match.getEntryA(), name(names, match.getEntryA()),
                match.getEntryB(), name(names, match.getEntryB()),
                match.getWinnerEntry(), name(names, match.getWinnerEntry()),
                match.getNextMatchId(), match.isBye(), match.getStationId(), match.getStartedAt(),
                match.getExtraMin(), match.getDecidedBy(), match.getDecidedAt());
    }

    private static String name(Function<Long, String> names, Long entryId) {
        return entryId == null ? null : names.apply(entryId);
    }
}
