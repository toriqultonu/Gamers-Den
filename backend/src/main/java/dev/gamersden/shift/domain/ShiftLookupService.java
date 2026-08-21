package dev.gamersden.shift.domain;

import dev.gamersden.common.spi.ShiftLookup;
import dev.gamersden.shift.repo.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The {@code shift} package's answer to {@link ShiftLookup} — the only door {@code auth} uses into
 * the {@code shifts} table. Open/close, X/Z math and expenses arrive with B11.
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
