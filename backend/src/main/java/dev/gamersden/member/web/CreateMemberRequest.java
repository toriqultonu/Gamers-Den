package dev.gamersden.member.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * {@code POST /members} (api-contract.md, Members). The phone is stored normalised — separators
 * are dropped — so the same customer typed two ways is 409 {@code DUPLICATE_PHONE}, not a second
 * row.
 */
public record CreateMemberRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 32) String phone,
        @Schema(description = "PS5 or PS4 — what the desk seats them on by default")
        @Size(max = 16) String preferredConsole,
        @Schema(description = "Free-text favourites shown on the member card")
        List<String> games) {
}
