package dev.gamersden.settings.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The three accent colours S13 offers — "Den Red (default), Blue, Green" (design.md §3 and §6).
 *
 * <p>{@code terminal_settings.accent} is a TEXT column holding the hex, because that is the DDL
 * docs/backend-architecture.md §2 fixes and the frontend reads a hex. The closed set lives here
 * rather than in the column: design.md wins on UI, and every accent needs a full tonal ramp
 * (100–900, both themes) on the frontend side — an arbitrary hex would arrive with no ramp behind
 * it and quietly break the contrast rules §3 spells out.
 */
public enum Accent {

    DEN_RED("#ec3013"),
    BLUE("#0f62fe"),
    GREEN("#198038");

    /** What the column defaults to, and what a terminal with no row of its own renders. */
    public static final Accent DEFAULT = DEN_RED;

    private final String hex;

    Accent(String hex) {
        this.hex = hex;
    }

    public String hex() {
        return hex;
    }

    /** Case-insensitive: {@code #EC3013} and {@code #ec3013} are the same swatch. */
    public static Optional<Accent> ofHex(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(accent -> accent.hex.equals(normalised)).findFirst();
    }

    /** For the error message, so the caller learns what it may send instead. */
    public static String allowedHexes() {
        return String.join(", ", Arrays.stream(values()).map(Accent::hex).toList());
    }
}
