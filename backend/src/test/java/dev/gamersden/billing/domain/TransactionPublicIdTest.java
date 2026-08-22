package dev.gamersden.billing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code transactions.public_id} — the id printed and read back over the phone. */
class TransactionPublicIdTest {

    @Test
    @DisplayName("reads back as the shape V001 documents")
    void matchesTheDocumentedShape() {
        assertThat(TransactionPublicId.of(LocalDate.of(2026, 8, 26), 47)).isEqualTo("GD-2608-047");
    }

    @Test
    @DisplayName("the day prefix is what the day's count is taken over")
    void dayPrefixIsTheCountKey() {
        assertThat(TransactionPublicId.dayPrefix(LocalDate.of(2026, 8, 26))).isEqualTo("GD-2608-");
        assertThat(TransactionPublicId.of(LocalDate.of(2026, 8, 26), 47))
                .startsWith(TransactionPublicId.dayPrefix(LocalDate.of(2026, 8, 26)));
    }

    @Test
    @DisplayName("the first sale of the day is 001, not 000")
    void sequenceIsOneBased() {
        assertThat(TransactionPublicId.of(LocalDate.of(2026, 1, 1), 1)).isEqualTo("GD-0101-001");
    }

    @Test
    @DisplayName("a very busy day widens the number rather than wrapping it")
    void widensPastAThousand() {
        assertThat(TransactionPublicId.of(LocalDate.of(2026, 8, 26), 1_004)).isEqualTo("GD-2608-1004");
    }
}
