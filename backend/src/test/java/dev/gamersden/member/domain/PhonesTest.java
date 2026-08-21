package dev.gamersden.member.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone number is the member's identity, so 409 {@code DUPLICATE_PHONE} is only as good as
 * this normalisation: whatever separators the desk types, the same customer must land on the same
 * stored string.
 */
class PhonesTest {

    @Test
    @DisplayName("separators a human types are dropped, digits are kept in order")
    void separatorsAreDropped() {
        assertThat(Phones.normalise("017 1234-5678")).isEqualTo("01712345678");
        assertThat(Phones.normalise("(017) 1234 5678")).isEqualTo("01712345678");
        assertThat(Phones.normalise("  01712345678  ")).isEqualTo("01712345678");
    }

    @Test
    @DisplayName("a leading + survives because it carries the country code")
    void countryCodeSurvives() {
        assertThat(Phones.normalise("+880 1712 345678")).isEqualTo("+8801712345678");
        assertThat(Phones.normalise("880-1712-345678")).isEqualTo("8801712345678");
    }

    @Test
    @DisplayName("nothing else is guessed at — no local prefix, no country default")
    void nothingElseIsRewritten() {
        assertThat(Phones.normalise("01712345678")).isEqualTo("01712345678");
        assertThat(Phones.normalise("")).isEmpty();
        assertThat(Phones.normalise(null)).isEmpty();
        assertThat(Phones.normalise("no digits here")).isEmpty();
    }

    @Test
    @DisplayName("a search term yields its digits, or nothing when the operator typed a name")
    void searchDigits() {
        assertThat(Phones.digitsIn("017-1234")).isEqualTo("0171234");
        assertThat(Phones.digitsIn("+880 17")).isEqualTo("88017");
        assertThat(Phones.digitsIn("Rafi")).isEmpty();
    }
}
