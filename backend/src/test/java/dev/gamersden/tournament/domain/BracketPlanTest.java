package dev.gamersden.tournament.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bracket's arithmetic (docs/tournaments.md §3, invariant §5.6), proved without a database.
 *
 * <p>Two promises are being kept here. A cap of 4, 8, 16 or 32 is a <strong>perfect</strong>
 * bracket — exactly N−1 matches, every player in the first round, no byes — which is the whole
 * reason {@code max_players} is restricted to a power of two. And an undersubscribed event still
 * gets a bracket that plays: byes that advance one player each, never two byes meeting, never a
 * match with nobody in it.
 */
class BracketPlanTest {

    // ---- the caps ---------------------------------------------------------------------------

    @Nested
    @DisplayName("a full event — the only shape the auto-generate at cap fill ever draws")
    class FullCaps {

        @ParameterizedTest(name = "cap {0}")
        @ValueSource(ints = {4, 8, 16, 32})
        @DisplayName("has exactly N-1 matches and no byes")
        void perfectBracket(int cap) {
            BracketPlan plan = planFor(cap);

            assertThat(plan.size()).isEqualTo(cap);
            assertThat(plan.matches()).hasSize(cap - 1);
            assertThat(plan.byes()).isZero();
            assertThat(plan.matches()).allSatisfy(match ->
                    assertThat(match.round() == 1)
                            .as("only the first round has players at draw time")
                            .isEqualTo(match.entryA() != null && match.entryB() != null));
        }

        @ParameterizedTest(name = "cap {0}")
        @ValueSource(ints = {4, 8, 16, 32})
        @DisplayName("halves every round down to a single final")
        void roundsHalve(int cap) {
            BracketPlan plan = planFor(cap);

            assertThat(plan.rounds()).isEqualTo(Integer.numberOfTrailingZeros(cap));
            for (int round = 1; round <= plan.rounds(); round++) {
                assertThat(plan.roundOf(round))
                        .as("round %d of a %d bracket", round, cap)
                        .hasSize(cap >> round);
                assertThat(plan.roundOf(round)).extracting(BracketPlan.PlannedMatch::slot)
                        .containsExactlyElementsOf(slots(cap >> round));
            }
            assertThat(plan.roundOf(plan.rounds())).hasSize(1);
        }

        @ParameterizedTest(name = "cap {0}")
        @ValueSource(ints = {4, 8, 16, 32})
        @DisplayName("seats every player exactly once, in the first round")
        void everybodyPlaysOnce(int cap) {
            List<Long> drawn = new ArrayList<>();
            planFor(cap).roundOf(1).forEach(match -> {
                drawn.add(match.entryA());
                drawn.add(match.entryB());
            });

            assertThat(drawn).hasSize(cap).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(seeds(cap));
        }

        @ParameterizedTest(name = "cap {0}")
        @ValueSource(ints = {4, 8, 16, 32})
        @DisplayName("pairs the top half of the draw against the bottom half")
        void standardSeeding(int cap) {
            assertThat(planFor(cap).roundOf(1)).allSatisfy(match -> {
                long high = Math.min(match.entryA(), match.entryB());
                long low = Math.max(match.entryA(), match.entryB());
                assertThat(high).as("one side from the top half").isLessThanOrEqualTo(cap / 2);
                assertThat(low).as("the other from the bottom half").isGreaterThan(cap / 2);
                assertThat(high + low).as("seed s always meets seed N+1-s").isEqualTo(cap + 1);
            });
        }

        @Test
        @DisplayName("draws the classic eight — 1v8, 4v5, 2v7, 3v6")
        void theEightIsTheTextbookOne() {
            assertThat(BracketPlan.seedOrder(8)).containsExactly(1, 8, 4, 5, 2, 7, 3, 6);
            assertThat(BracketPlan.seedOrder(4)).containsExactly(1, 4, 2, 3);
            assertThat(BracketPlan.seedOrder(2)).containsExactly(1, 2);

            assertThat(planFor(8).roundOf(1))
                    .extracting(match -> match.entryA() + "v" + match.entryB())
                    .containsExactly("1v8", "4v5", "2v7", "3v6");
        }
    }

    // ---- the tree ---------------------------------------------------------------------------

    @ParameterizedTest(name = "cap {0}")
    @ValueSource(ints = {4, 8, 16, 32})
    @DisplayName("every match but the final feeds exactly one slot above, two feeders each")
    void theTreeIsAWellFormedBinaryTree(int cap) {
        BracketPlan plan = planFor(cap);

        for (int round = 1; round < plan.rounds(); round++) {
            Map<Integer, Long> feeders = plan.roundOf(round).stream()
                    .collect(Collectors.groupingBy(match -> (match.slot() + 1) / 2,
                            Collectors.counting()));
            assertThat(feeders).as("round %d feeding round %d", round, round + 1)
                    .hasSize(cap >> (round + 1))
                    .allSatisfy((slot, count) -> assertThat(count).isEqualTo(2));
        }
    }

    // ---- undersubscribed --------------------------------------------------------------------

    @Nested
    @DisplayName("an undersubscribed event — the manual generate")
    class Byes {

        @ParameterizedTest(name = "{0} players")
        @ValueSource(ints = {2, 3, 5, 6, 7, 9, 11, 15, 17, 23, 31})
        @DisplayName("plays the next power of two up, one bye per empty position")
        void nextPowerOfTwo(int players) {
            BracketPlan plan = planFor(players);
            int size = BracketPlan.bracketSize(players);

            assertThat(plan.size()).isEqualTo(size);
            assertThat(size).isGreaterThanOrEqualTo(players).isLessThan(players * 2);
            assertThat(plan.matches()).hasSize(size - 1);
            assertThat(plan.byes()).isEqualTo(size - players);
        }

        @ParameterizedTest(name = "{0} players")
        @ValueSource(ints = {2, 3, 5, 6, 7, 9, 11, 15, 17, 23, 31})
        @DisplayName("never leaves a match empty and never puts two byes together")
        void noGhostMatches(int players) {
            assertThat(planFor(players).roundOf(1)).allSatisfy(match ->
                    assertThat(match.entryA() != null || match.entryB() != null)
                            .as("round-1 match %d has at least one real player", match.slot())
                            .isTrue());
        }

        @ParameterizedTest(name = "{0} players")
        @ValueSource(ints = {2, 3, 5, 6, 7, 9, 11, 15, 17, 23, 31})
        @DisplayName("seats everybody once and gives the byes to the earliest seeds")
        void byesGoToTheFirstToBuyIn(int players) {
            BracketPlan plan = planFor(players);
            Set<Long> seated = new HashSet<>();
            List<Long> walkedThrough = new ArrayList<>();
            plan.roundOf(1).forEach(match -> {
                if (match.entryA() != null) {
                    seated.add(match.entryA());
                }
                if (match.entryB() != null) {
                    seated.add(match.entryB());
                }
                if (match.isBye()) {
                    walkedThrough.add(match.soleEntry());
                }
            });

            assertThat(seated).containsExactlyInAnyOrderElementsOf(seeds(players));
            assertThat(walkedThrough).hasSize(plan.size() - players).doesNotHaveDuplicates()
                    .allSatisfy(seed -> assertThat(seed)
                            .isLessThanOrEqualTo((long) plan.size() - players));
        }

        @Test
        @DisplayName("five players play an eight-slot bracket — seeds 1, 2 and 3 walk through")
        void fiveInAnEight() {
            BracketPlan plan = planFor(5);

            assertThat(plan.size()).isEqualTo(8);
            assertThat(plan.matches()).hasSize(7);
            assertThat(plan.roundOf(1))
                    .extracting(match -> match.entryA() + "v" + match.entryB())
                    .containsExactly("1vnull", "4v5", "2vnull", "3vnull");
            assertThat(plan.byes()).isEqualTo(3);
        }

        @Test
        @DisplayName("two players are one match and no byes at all")
        void theSmallestBracket() {
            BracketPlan plan = planFor(2);

            assertThat(plan.size()).isEqualTo(2);
            assertThat(plan.rounds()).isEqualTo(1);
            assertThat(plan.matches()).hasSize(1);
            assertThat(plan.byes()).isZero();
            assertThat(plan.matches().get(0).entryA()).isEqualTo(1L);
            assertThat(plan.matches().get(0).entryB()).isEqualTo(2L);
        }

        @Test
        @DisplayName("one player is not a tournament — the caller turns this into NOT_ENOUGH_PLAYERS")
        void aloneIsRefused() {
            assertThatThrownBy(() -> planFor(1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 2");
            assertThatThrownBy(() -> BracketPlan.forSeeds(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BracketPlan.forSeeds(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Entry ids standing in for seeds, so a failure names the seed that moved. */
    private static BracketPlan planFor(int players) {
        return BracketPlan.forSeeds(seeds(players));
    }

    private static List<Long> seeds(int players) {
        return IntStream.rangeClosed(1, players).mapToObj(Long::valueOf).toList();
    }

    private static List<Integer> slots(int count) {
        return IntStream.rangeClosed(1, count).boxed().map(Function.identity()).toList();
    }
}
