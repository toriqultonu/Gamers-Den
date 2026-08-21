package dev.gamersden.auth.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/** {@code PUT /me/prefs} — {@code null} resets to the default swatch (design.md S13). */
@Schema(name = "PrefsRequest")
public record PrefsRequest(
        @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "avatarColor must be a #rrggbb hex colour")
        @Schema(example = "#ec3013", nullable = true)
        String avatarColor) {
}
