package dev.gamersden.common.security;

import dev.gamersden.auth.domain.StaffRole;

/**
 * The authenticated caller, reconstructed from the access-token claims on every request —
 * {@code sub}, {@code role}, {@code shiftId?}, {@code terminal} (api-contract.md §1 "Auth").
 *
 * <p>Deliberately claim-shaped and nothing more: no display name, no PIN state, no DB round-trip
 * per request. Anything else a handler needs is loaded from the owning package's service.
 *
 * @param id       {@code staff.id} — the token's {@code sub}
 * @param role     ADMIN &gt; MANAGER &gt; CASHIER (ARCHITECTURE.md §4.6)
 * @param shiftId  the terminal's open shift at token-issue time, or {@code null} when none
 * @param terminal the POS terminal the token was issued to
 */
public record StaffPrincipal(Long id, StaffRole role, Long shiftId, String terminal) {

    public boolean isAtLeast(StaffRole minimum) {
        return role.ordinal() <= minimum.ordinal();
    }
}
