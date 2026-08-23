package dev.gamersden.common.spi;

/**
 * The one thing {@code printing} needs out of {@code terminal_settings} — how many copies of a
 * sale ticket this terminal prints (S13 "Receipt copies 1 / 2"). Implemented by
 * {@code settings/domain/TerminalSettingsService}, so the print queue never reaches for
 * {@code TerminalSettingsRepository} (ARCHITECTURE.md §3).
 *
 * <p>Read inside the money transaction, because that is where the sale ticket is rendered and
 * docs/backend-architecture.md §5 puts the second copy "inside the same job, after the cut" — the
 * setting has to be known before the bytes are stored, not when they are sent.
 */
public interface ReceiptCopyPreference {

    /**
     * 1 or 2. A terminal that has never opened S13 has no settings row and prints one copy — the
     * column's own {@code DEFAULT 1}, answered the same way here so a missing row and a default
     * row behave identically.
     */
    int receiptCopies(String terminal);
}
