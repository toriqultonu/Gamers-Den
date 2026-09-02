package dev.gamersden.settings.domain;

/**
 * A whole settings object on its way in from {@code PUT /terminal-settings} — the seven fields
 * docs/api-contract.md (Settings) lists, and nothing else.
 *
 * <p>A replace rather than a patch, deliberately: S13 holds the complete state on screen and
 * sends it back, and "remove the background" (design.md §6) has to be expressible. A patch shape
 * cannot tell an omitted {@code loginBgImageId} from one explicitly set to null, so removal would
 * have needed an endpoint of its own that the contract does not have.
 *
 * @param theme          DARK (default) or LIGHT
 * @param fontScale      COMPACT / DEFAULT / LARGE
 * @param accent         one of the three swatches, as hex
 * @param loginBgImageId the terminal's uploaded background, or null to remove it
 * @param sound          alert &amp; time-up sound
 * @param autoLockMin    0 = off, else 2, 5 or 10 minutes
 * @param receiptCopies  1 or 2
 */
public record TerminalSettingsUpdate(Theme theme,
                                     FontScale fontScale,
                                     String accent,
                                     String loginBgImageId,
                                     boolean sound,
                                     int autoLockMin,
                                     int receiptCopies) {
}
