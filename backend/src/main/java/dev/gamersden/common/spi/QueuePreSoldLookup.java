package dev.gamersden.common.spi;

/**
 * The narrow read the {@code report} package needs from {@code queue} — the walk-up half of S2's
 * pre-sold stat — without reaching for {@code QueueEntryRepository} (ARCHITECTURE.md §3).
 *
 * <p>Only {@code PLAY_TICKET} tokens are counted. docs/bookings.md §6 defines pre-sold as PAID
 * bookings plus WAITING play tickets, and a token issued at a booking's check-in belongs to a
 * booking that has already left the PAID column — counting it here would bill the same money to
 * the stat twice.
 */
public interface QueuePreSoldLookup {

    /** Walk-up tokens sold and still waiting for a seat. */
    PreSoldTokens waitingPlayTickets();

    /** @param amount the rate snapshots the tokens were sold at, not what the rate card says now */
    record PreSoldTokens(int tokens, int amount) {
    }
}
