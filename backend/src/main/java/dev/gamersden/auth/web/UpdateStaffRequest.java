package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code PATCH /staff/{id}} — Admin. Every field optional; omitted fields are left alone. */
@Schema(name = "UpdateStaffRequest")
public record UpdateStaffRequest(
        @Size(min = 1, max = 60) String name,
        @Schema(allowableValues = {"MANAGER", "CASHIER"}) StaffRole role,
        @Pattern(regexp = "[0-9]{4}", message = "pin must be 4 digits") String pin,
        Boolean active) {
}
