package dev.gamersden.tournament.web;

import dev.gamersden.tournament.domain.ConsoleAvailability;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One allocated console on the cashier job board, and why it can or cannot take the next match
 * (docs/tournaments.md §4).
 *
 * <p>{@code state} is what the UI switches on; {@code note} is the line it shows — including the
 * "Allocated console busy with a walk-in session" the spec calls out by name, which is the case an
 * operator would otherwise mistake for a bug.
 */
@Schema(name = "ConsoleAvailability")
public record ConsoleAvailabilityView(long stationId,
                                      String stationName,
                                      ConsoleAvailability.State state,
                                      boolean available,
                                      @Schema(description = "The match occupying it, if any")
                                      Long matchId,
                                      String note) {

    public static List<ConsoleAvailabilityView> of(List<ConsoleAvailability> consoles) {
        return consoles.stream().map(ConsoleAvailabilityView::of).toList();
    }

    public static ConsoleAvailabilityView of(ConsoleAvailability console) {
        return new ConsoleAvailabilityView(console.stationId(), console.stationName(),
                console.state(), console.isFree(), console.matchId(), console.note());
    }
}
