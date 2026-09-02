package dev.gamersden.settings.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.gamersden.settings.domain.FontScale;
import dev.gamersden.settings.domain.TerminalSettings;
import dev.gamersden.settings.domain.Theme;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code GET/PUT /terminal-settings} — exactly the seven fields api-contract.md (Settings) lists:
 * {@code {theme, fontScale, accent, loginBgImageId?, sound, autoLockMin, receiptCopies}}.
 *
 * <p>{@code loginBgImageId} is serialised even when null, so S13 can tell "no background" from a
 * field it failed to read; {@code null} is the state the ImagePicker draws as empty.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "TerminalSettings", description = "Per-terminal appearance and behaviour (design.md §6)")
public record TerminalSettingsView(Theme theme,
                                   FontScale fontScale,
                                   @Schema(example = "#ec3013") String accent,
                                   @Schema(nullable = true) String loginBgImageId,
                                   boolean sound,
                                   @Schema(description = "0 = off, else 2, 5 or 10") int autoLockMin,
                                   @Schema(description = "1 or 2") int receiptCopies) {

    public static TerminalSettingsView of(TerminalSettings settings) {
        return new TerminalSettingsView(settings.getTheme(), settings.getFontScale(),
                settings.getAccent(), settings.getLoginBgImageId(), settings.isSound(),
                settings.getAutoLockMin(), settings.getReceiptCopies());
    }
}
