package dev.gamersden.settings.web;

import dev.gamersden.settings.domain.FontScale;
import dev.gamersden.settings.domain.TerminalSettingsUpdate;
import dev.gamersden.settings.domain.Theme;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /terminal-settings} — Admin only (api-contract.md §1's matrix: "terminal settings
 * write" is an Admin row).
 *
 * <p>The whole object, not a patch: S13 already holds every control's value on screen, and
 * "remove the background" has to be sendable as {@code loginBgImageId: null} — see
 * {@link TerminalSettingsUpdate}. The closed sets behind {@code accent}, {@code autoLockMin} and
 * {@code receiptCopies} are checked in the service, where they hold for any caller; bean
 * validation here only insists the fields arrived.
 */
@Schema(name = "UpdateTerminalSettingsRequest")
public record UpdateTerminalSettingsRequest(@NotNull Theme theme,
                                            @NotNull FontScale fontScale,
                                            @NotNull @Schema(example = "#ec3013") String accent,
                                            @Schema(nullable = true,
                                                    description = "the id this terminal's last "
                                                            + "upload returned, or null to remove")
                                            String loginBgImageId,
                                            @NotNull Boolean sound,
                                            @NotNull @Schema(description = "0 = off, else 2, 5 or 10")
                                            Integer autoLockMin,
                                            @NotNull @Schema(description = "1 or 2") Integer receiptCopies) {

    public TerminalSettingsUpdate toUpdate() {
        return new TerminalSettingsUpdate(theme, fontScale, accent, loginBgImageId, sound,
                autoLockMin, receiptCopies);
    }
}
