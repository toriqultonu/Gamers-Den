package dev.gamersden.tournament.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a bracket, worked out before a single row is written (docs/tournaments.md §3).
 *
 * <p>Pure arithmetic on purpose: the bracket is the one part of this module with a right answer
 * that does not depend on the database, and keeping it here means the caps can be proved
 * exhaustively without a container.
 *
 * <h2>Size</h2>
 * The bracket is the smallest power of two that holds everybody. A full event therefore lands
 * exactly on its cap — 4, 8, 16 or 32 — and produces a perfect bracket with {@code N−1} matches
 * and no byes, which is what restricting {@code max_players} to 2ⁿ buys (§3). An undersubscribed
 * one gets the next size up from its head count, not its cap: five players play an eight-slot
 * bracket with three byes, never a thirty-two-slot one with twenty-seven.
 *
 * <h2>Placement</h2>
 * Seeds are placed in the standard bracket order — 1 against the last, 2 in the opposite half, and
 * so on recursively ({@code [1,2]} → {@code [1,4,2,3]} → {@code [1,8,4,5,2,7,3,6]}). Seeds here are
 * sale order rather than skill, so this is not about protecting a favourite; it is about where the
 * empty positions fall. Every first-round pairing takes one position from the top half of the
 * draw and one from the bottom, and the empty positions are always the highest numbers — so a bye
 * can never land on both sides of a match, and can never reach the second round:
 *
 * <ul>
 *   <li>every first-round match has at least one real player, so no match is a ghost;</li>
 *   <li>at most one side of a match is empty, so a bye advances exactly one player;</li>
 *   <li>the byes fall to the earliest seeds — the players who bought in first.</li>
 * </ul>
 *
 * <p>That holds because the size is the <em>next</em> power of two: the empty count is
 * {@code size − players < size/2}, and the top half of the draw is never empty.
 *
 * @param size    bracket positions, a power of two ≥ the head count
 * @param rounds  log₂(size) — round 1 is the first round, round {@code rounds} is the final
 * @param matches every node, first round first, {@code size − 1} of them
 */
public record BracketPlan(int size, int rounds, List<PlannedMatch> matches) {

    /** The fewest players a bracket can be drawn for (§3, 409 {@code NOT_ENOUGH_PLAYERS}). */
    public static final int MIN_PLAYERS = 2;

    /**
     * One node before it has an id.
     *
     * @param entryA the top side, {@code null} when the bracket position is empty
     * @param entryB the bottom side, {@code null} when the bracket position is empty
     */
    public record PlannedMatch(int round, int slot, Long entryA, Long entryB) {

        /** One player, one empty position: decided by the draw itself. */
        public boolean isBye() {
            return (entryA == null) != (entryB == null);
        }

        /** Whoever the bye advances. */
        public Long soleEntry() {
            return entryA != null ? entryA : entryB;
        }
    }

    /**
     * Draws the bracket for these entries, in seed order.
     *
     * @param entryIds the players, seed 1 first
     * @throws IllegalArgumentException below {@link #MIN_PLAYERS} — callers turn that into the 409
     */
    public static BracketPlan forSeeds(List<Long> entryIds) {
        int players = entryIds == null ? 0 : entryIds.size();
        if (players < MIN_PLAYERS) {
            throw new IllegalArgumentException("A bracket needs at least " + MIN_PLAYERS + " players");
        }
        int size = bracketSize(players);
        int rounds = Integer.numberOfTrailingZeros(size);
        int[] order = seedOrder(size);

        List<PlannedMatch> matches = new ArrayList<>(size - 1);
        for (int slot = 1; slot <= size / 2; slot++) {
            matches.add(new PlannedMatch(1, slot,
                    entryAt(entryIds, order[2 * slot - 2]), entryAt(entryIds, order[2 * slot - 1])));
        }
        for (int round = 2; round <= rounds; round++) {
            for (int slot = 1; slot <= size >> round; slot++) {
                matches.add(new PlannedMatch(round, slot, null, null));
            }
        }
        return new BracketPlan(size, rounds, List.copyOf(matches));
    }

    /** The smallest power of two that seats this many players. */
    public static int bracketSize(int players) {
        int size = 1;
        while (size < players) {
            size <<= 1;
        }
        return size;
    }

    /**
     * The standard seed order for a bracket of {@code size} positions: read two at a time, it is
     * the first round. Built by reflection — each round doubles the field, and every existing seed
     * {@code s} is answered by {@code size + 1 − s}.
     */
    public static int[] seedOrder(int size) {
        int[] order = {1};
        while (order.length < size) {
            int grown = order.length * 2;
            int[] next = new int[grown];
            for (int i = 0; i < order.length; i++) {
                next[2 * i] = order[i];
                next[2 * i + 1] = grown + 1 - order[i];
            }
            order = next;
        }
        return order;
    }

    /** Just one round of the draw, left to right — how the writer walks the tree. */
    public List<PlannedMatch> roundOf(int round) {
        return matches.stream().filter(match -> match.round() == round).toList();
    }

    /** How many empty positions the draw carries — the number of byes. */
    public int byes() {
        return (int) matches.stream().filter(match -> match.round() == 1 && match.isBye()).count();
    }

    /** The seed at a bracket position, or {@code null} when the position is one of the empties. */
    private static Long entryAt(List<Long> entryIds, int position) {
        return position <= entryIds.size() ? entryIds.get(position - 1) : null;
    }
}
