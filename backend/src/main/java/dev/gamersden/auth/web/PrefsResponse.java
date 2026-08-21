package dev.gamersden.auth.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** {@code GET/PUT /me/prefs} — api-contract.md §2 "Settings". */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "PrefsResponse")
public record PrefsResponse(@Schema(example = "#ec3013", nullable = true) String avatarColor) {
}
