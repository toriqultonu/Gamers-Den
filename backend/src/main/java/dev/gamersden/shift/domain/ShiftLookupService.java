package dev.gamersden.shift.domain;

import dev.gamersden.common.spi.ShiftLookup;
import dev.gamersden.shift.repo.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The {@code shift} package's answer to {@link ShiftLookup} — the only door {@code auth} and
 * {@code billing} use into the {@code shifts} table (ARCHITECTURE.md §3). The lifecycle itself
 * lives in {@link ShiftService}; this is the read every other package needs from it.
 */
@Service
public class ShiftLookupService implements ShiftLookup {

    private final ShiftRepository shifts;

    public ShiftLookupService(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> openShiftId(String terminal) {
        return shifts.findByTerminalAndClosedAtIsNull(terminal).map(Shift::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenShift(long staffId) {
        return shifts.existsByStaffIdAndClosedAtIsNull(staffId);
    }
}
