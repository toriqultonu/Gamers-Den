package dev.gamersden.common.security;

/**
 * SpEL guards for {@code @PreAuthorize}, so every feature package spells the permission matrix
 * (api-contract.md §1) the same way. The matrix is API-enforced; UI hiding is cosmetic.
 *
 * <pre>{@code @PreAuthorize(Roles.ADMIN)}</pre>
 */
public final class Roles {

    /** Stations CRUD, pricing, staff CRUD, terminal-settings write, booking settings. */
    public static final String ADMIN = "hasRole('ADMIN')";

    /** Manager or Admin: menu &amp; stock, tournament configuration, void/reprint, reports. */
    public static final String MANAGER_PLUS = "hasAnyRole('ADMIN','MANAGER')";

    /** Any signed-in operator: sessions, POS, payments, prints, bookings, own prefs. */
    public static final String ANY_STAFF = "isAuthenticated()";

    private Roles() {
    }
}
