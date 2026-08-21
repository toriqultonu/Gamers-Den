package dev.gamersden.auth.domain;

import dev.gamersden.auth.repo.StaffRepository;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.ShiftLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Staff CRUD (Admin) and the per-staff profile pref (any role) — api-contract.md §2 "Auth &amp; staff".
 *
 * <p>Delete is a deactivation, not a row removal: {@code shifts}, {@code sessions} and
 * {@code transactions} all point at {@code staff}, and the audit trail outlives the employee.
 * A deactivated account cannot log in and its live refresh tokens are revoked immediately.
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    private final StaffRepository staff;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder pins;
    private final ShiftLookup shifts;

    public StaffService(StaffRepository staff,
                        RefreshTokenService refreshTokens,
                        PasswordEncoder pins,
                        ShiftLookup shifts) {
        this.staff = staff;
        this.refreshTokens = refreshTokens;
        this.pins = pins;
        this.shifts = shifts;
    }

    @Transactional(readOnly = true)
    public List<Staff> list() {
        return staff.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Transactional(readOnly = true)
    public Staff get(Long id) {
        return staff.findById(id).orElseThrow(() -> new NotFoundException("Staff", id));
    }

    @Transactional
    public Staff create(String name, StaffRole role, String pin) {
        String trimmed = name.trim();
        requireHireableRole(role);
        if (staff.existsByName(trimmed)) {
            throw duplicateName(trimmed);
        }
        Staff created = staff.save(new Staff(trimmed, role, pins.encode(pin)));
        log.info("staff {} created as {}", created.getId(), role);
        return created;
    }

    /** Every field optional; a supplied PIN is re-hashed and cuts the account's live sessions. */
    @Transactional
    public Staff update(Long id, String name, StaffRole role, String pin, Boolean active) {
        Staff account = get(id);
        if (name != null) {
            String trimmed = name.trim();
            if (!trimmed.equalsIgnoreCase(account.getName()) && staff.existsByName(trimmed)) {
                throw duplicateName(trimmed);
            }
            account.setName(trimmed);
        }
        if (role != null) {
            requireHireableRole(role);
            account.setRole(role);
        }
        if (pin != null) {
            account.setPinHash(pins.encode(pin));
            account.setFailedPins(0);
            account.setLockedUntil(null);
            refreshTokens.revokeAllForStaff(id);
        }
        if (active != null && active != account.isActive()) {
            if (!active) {
                requireOffShift(account);
                refreshTokens.revokeAllForStaff(id);
            }
            account.setActive(active);
        }
        log.info("staff {} updated", id);
        return account;
    }

    @Transactional
    public void deactivate(Long id) {
        Staff account = get(id);
        requireOffShift(account);
        account.setActive(false);
        refreshTokens.revokeAllForStaff(id);
        log.info("staff {} deactivated", id);
    }

    @Transactional
    public Staff updateAvatarColor(Long id, String avatarColor) {
        Staff account = get(id);
        account.setAvatarColor(avatarColor);
        return account;
    }

    private void requireOffShift(Staff account) {
        if (shifts.hasOpenShift(account.getId())) {
            throw new ConflictException(ErrorCode.STAFF_ON_SHIFT,
                    "%s still has an open shift".formatted(account.getName()),
                    Map.of("staffId", account.getId()));
        }
    }

    /**
     * The contract only ever hires MANAGER or CASHIER; ADMIN is the seeded bootstrap account
     * (V001) and is not handed out through the API.
     */
    private static void requireHireableRole(StaffRole role) {
        if (role == StaffRole.ADMIN) {
            throw ValidationFailedException.onField("role", "role must be MANAGER or CASHIER");
        }
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException(ErrorCode.DUPLICATE_NAME,
                "Staff name \"%s\" is already taken".formatted(name), Map.of("name", name));
    }
}
