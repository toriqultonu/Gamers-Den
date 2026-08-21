package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /staff} — Admin. Only MANAGER or CASHIER can be hired through the API. */
@Schema(name = "CreateStaffRequest")
public record CreateStaffRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull @Schema(allowableValues = {"MANAGER", "CASHIER"}) StaffRole role,
        @NotBlank @Pattern(regexp = "[0-9]{4}", message = "pin must be 4 digits") String pin) {
}
