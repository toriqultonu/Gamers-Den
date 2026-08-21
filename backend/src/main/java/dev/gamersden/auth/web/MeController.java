package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.StaffService;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET/PUT /me/prefs} — the signed-in operator's own profile pref (design.md S13). */
@RestController
@RequestMapping("/me")
@Tag(name = "Me")
@PreAuthorize(Roles.ANY_STAFF)
public class MeController {

    private final StaffService staff;

    public MeController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping("/prefs")
    @Operation(summary = "Read my profile prefs")
    public PrefsResponse read() {
        return new PrefsResponse(staff.get(CurrentStaff.require().id()).getAvatarColor());
    }

    @PutMapping("/prefs")
    @Operation(summary = "Set my avatar colour", description = "null resets to the default swatch.")
    public PrefsResponse update(@Valid @RequestBody PrefsRequest request) {
        return new PrefsResponse(
                staff.updateAvatarColor(CurrentStaff.require().id(), request.avatarColor())
                        .getAvatarColor());
    }
}
