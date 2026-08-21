package dev.gamersden.auth.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /auth/login} body — api-contract.md §2 "Auth &amp; staff". */
@Schema(name = "LoginRequest")
public record LoginRequest(
        @NotNull @Schema(example = "1", description = "staff.id chosen on the login screen")
        Long staffId,

        @NotBlank @Pattern(regexp = "[0-9]{4}", message = "pin must be 4 digits")
        @Schema(example = "1234", description = "4-digit PIN; never logged")
        String pin,

        @NotBlank @Size(max = 32) @Schema(example = "T1", description = "POS terminal identifier")
        String terminal) {
}
