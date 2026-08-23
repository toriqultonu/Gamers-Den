package dev.gamersden.settings.domain;

import dev.gamersden.common.spi.ReceiptCopyPreference;
import dev.gamersden.settings.repo.TerminalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code settings} package's door onto {@code terminal_settings} (ARCHITECTURE.md §3).
 *
 * <p>Read-only for now, and deliberately so: B21 owns {@code GET/PUT /terminal-settings} and the
 * login-background upload. B18 needs one field of it — the receipt-copy count that decides whether
 * a sale ticket carries its copy after the cut — and the layering rule says the way to get it is
 * to ask this package, not to reach into its table.
 */
@Service
public class TerminalSettingsService implements ReceiptCopyPreference {

    /** What the column defaults to, and what a terminal with no row of its own prints. */
    private static final int DEFAULT_COPIES = 1;

    private final TerminalSettingsRepository settings;

    public TerminalSettingsService(TerminalSettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public int receiptCopies(String terminal) {
        if (terminal == null || terminal.isBlank()) {
            return DEFAULT_COPIES;
        }
        return settings.findById(terminal)
                .map(TerminalSettings::getReceiptCopies)
                .orElse(DEFAULT_COPIES);
    }
}
