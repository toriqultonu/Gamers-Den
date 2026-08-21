package dev.gamersden.common.spi;

import java.util.Optional;

/**
 * The narrow read the {@code auth} package needs from {@code shift} — the {@code shiftId} claim
 * and the {@code STAFF_ON_SHIFT} delete guard — without reaching for {@code ShiftRepository}
 * (ARCHITECTURE.md §3: no cross-package repository access, call the owning package's service).
 *
 * <p>Implemented by {@code shift/domain/ShiftLookupService}. The full shift lifecycle lands in B11.
 */
public interface ShiftLookup {

    /** The open shift on {@code terminal}, or empty when nobody has opened one yet. */
    Optional<Long> openShiftId(String terminal);

    /** True while this staff member has a shift open — blocks deletion with 409 STAFF_ON_SHIFT. */
    boolean hasOpenShift(long staffId);
}
