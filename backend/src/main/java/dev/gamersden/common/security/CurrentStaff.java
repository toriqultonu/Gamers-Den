package dev.gamersden.common.security;

import dev.gamersden.common.error.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/** Reads the caller off the {@code SecurityContext} without threading it through every signature. */
public final class CurrentStaff {

    private CurrentStaff() {
    }

    public static Optional<StaffPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof StaffAuthentication staff && staff.isAuthenticated()) {
            return Optional.of(staff.getPrincipal());
        }
        return Optional.empty();
    }

    /** The caller on a guarded route; only throws if a route was left off the filter chain. */
    public static StaffPrincipal require() {
        return find().orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }
}
