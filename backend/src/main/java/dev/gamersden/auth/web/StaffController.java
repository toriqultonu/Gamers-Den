package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.StaffService;
import dev.gamersden.common.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /staff} — Admin only (api-contract.md §1 permission matrix). The guard is the
 * {@code @PreAuthorize} here, not the sidebar; a cashier calling these gets the 403 envelope.
 */
@RestController
@RequestMapping("/staff")
@Tag(name = "Staff")
@PreAuthorize(Roles.ADMIN)
public class StaffController {

    private final StaffService staff;

    public StaffController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping
    @Operation(summary = "List staff (Admin)")
    public List<StaffView> list() {
        return staff.list().stream().map(StaffView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a Manager or Cashier with a PIN (Admin)",
            description = "409 DUPLICATE_NAME when the name is taken.")
    public StaffView create(@Valid @RequestBody CreateStaffRequest request) {
        return StaffView.of(staff.create(request.name(), request.role(), request.pin()));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit a staff member (Admin)",
            description = "409 DUPLICATE_NAME on a taken name; a new PIN clears any lock and "
                    + "revokes that account's live refresh tokens.")
    public StaffView update(@PathVariable Long id, @Valid @RequestBody UpdateStaffRequest request) {
        return StaffView.of(staff.update(id, request.name(), request.role(),
                request.pin(), request.active()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a staff member (Admin)",
            description = "Deactivates rather than deletes — shifts, sessions and transactions "
                    + "keep pointing at the row. 409 STAFF_ON_SHIFT while a shift is open.")
    public void delete(@PathVariable Long id) {
        staff.deactivate(id);
    }
}
