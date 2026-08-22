package dev.gamersden.tournament.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two pure rules an entry carries: whose name is on the stub, and what the QR is made of. */
class TournamentEntryTest {

    @Test
    @DisplayName("the typed name wins, then the member's, then \"Walk-in guest\"")
    void nameFallsBackInOrder() {
        assertThat(TournamentEntry.nameFor("Rifat", "Nafis")).isEqualTo("Rifat");
        assertThat(TournamentEntry.nameFor("  Rifat  ", null)).isEqualTo("Rifat");
        assertThat(TournamentEntry.nameFor(null, "Nafis Iqbal")).isEqualTo("Nafis Iqbal");
        assertThat(TournamentEntry.nameFor("   ", "Nafis Iqbal")).isEqualTo("Nafis Iqbal");
        assertThat(TournamentEntry.nameFor(null, null)).isEqualTo("Walk-in guest");
        assertThat(TournamentEntry.nameFor("", "  ")).isEqualTo("Walk-in guest");
    }

    @Test
    @DisplayName("a QR token is 128 opaque bits, and no two are alike")
    void qrTokensAreRandom() {
        Set<String> tokens = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> tokens.add(TournamentEntry.newQrToken()));

        assertThat(tokens).hasSize(1_000);
        assertThat(tokens).allSatisfy(token -> assertThat(token).hasSize(32).matches("[0-9a-f]+"));
    }

    @Test
    @DisplayName("only a power-of-two cap builds a perfect bracket")
    void capsAreRestricted() {
        assertThat(Tournament.CAPS).containsExactlyInAnyOrder(4, 8, 16, 32);
        for (int cap : new int[] {4, 8, 16, 32}) {
            assertThat(Tournament.requireValidCap(cap)).isEqualTo(cap);
        }
        for (int cap : new int[] {0, 1, 2, 3, 6, 12, 64}) {
            assertThatThrownBy(() -> Tournament.requireValidCap(cap))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("only OPEN and LIVE events hold their consoles")
    void statusIsTheReleaseMechanism() {
        assertThat(TournamentStatus.OPEN.holdsStations()).isTrue();
        assertThat(TournamentStatus.LIVE.holdsStations()).isTrue();
        assertThat(TournamentStatus.DONE.holdsStations()).isFalse();
        assertThat(TournamentStatus.CANCELLED.holdsStations()).isFalse();
        assertThat(TournamentStatus.DONE.isFinished()).isTrue();
        assertThat(TournamentStatus.CANCELLED.isFinished()).isTrue();
        assertThat(TournamentStatus.OPEN.isFinished()).isFalse();
    }
}
