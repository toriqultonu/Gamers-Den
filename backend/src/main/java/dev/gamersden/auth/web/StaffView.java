package dev.gamersden.auth.web;

import dev.gamersden.auth.domain.Staff;
import dev.gamersden.auth.domain.StaffRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/** The staff row the login screen and the Admin staff table render. Never carries the PIN hash. */
@Schema(name = "Staff")
public record StaffView(
        Long id,
        String name,
        StaffRole role,
        String avatarColor,
        boolean active,
        OffsetDateTime createdAt) {

    public static StaffView of(Staff staff) {
        return new StaffView(staff.getId(), staff.getName(), staff.getRole(),
                staff.getAvatarColor(), staff.isActive(), staff.getCreatedAt());
    }
}
