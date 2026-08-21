package dev.gamersden.station.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.station.repo.PricingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * {@code GET/PUT /pricing} — the console rate card (api-contract.md, "Stations &amp; pricing").
 *
 * <p>Edits apply to <strong>new blocks only</strong>. Nothing here ever touches
 * {@code session_blocks}: every block snapshots {@link #blockPrice} at purchase, so a running
 * session keeps the prices it bought no matter what an Admin changes mid-evening. The morning
 * window is the OPEN FLAG from ARCHITECTURE.md — the V001 defaults (10:00-14:00, -25%) stand
 * until the venue confirms, and are editable here rather than guessed in code.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private final PricingRepository pricing;
    private final Clock clock;

    public PricingService(PricingRepository pricing, Clock clock) {
        this.pricing = pricing;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Pricing> list() {
        return pricing.findAll().stream()
                .sorted(Comparator.comparing(Pricing::getConsoleType))
                .toList();
    }

    @Transactional(readOnly = true)
    public Pricing get(ConsoleType consoleType) {
        return pricing.findById(consoleType)
                .orElseThrow(() -> new NotFoundException("Pricing", consoleType));
    }

    /** The half-hour block price right now — what {@code POST /sessions/{id}/blocks} snapshots. */
    @Transactional(readOnly = true)
    public int blockPrice(ConsoleType consoleType) {
        return blockPrice(consoleType, VenueTime.now(clock));
    }

    /** The half-hour block price at an instant; the morning window is evaluated in venue time. */
    @Transactional(readOnly = true)
    public int blockPrice(ConsoleType consoleType, OffsetDateTime at) {
        return get(consoleType).blockPriceAt(at.atZoneSameInstant(VenueTime.ZONE).toLocalTime());
    }

    /**
     * Every field optional; omitted fields keep their stored value. Existing blocks are untouched
     * by design — the snapshot on {@code session_blocks.price} is the price that was sold.
     */
    @Transactional
    public Pricing update(ConsoleType consoleType,
                          Integer perHour,
                          Integer perHalfHour,
                          Integer morningDiscountPct,
                          LocalTime morningStart,
                          LocalTime morningEnd) {
        Pricing rate = get(consoleType);
        if (perHour != null) {
            requirePositive("perHour", perHour);
            rate.setPerHour(perHour);
        }
        if (perHalfHour != null) {
            requirePositive("perHalfHour", perHalfHour);
            rate.setPerHalfHour(perHalfHour);
        }
        if (morningDiscountPct != null) {
            if (morningDiscountPct < 0 || morningDiscountPct > 100) {
                throw ValidationFailedException.onField("morningDiscountPct",
                        "morningDiscountPct must be between 0 and 100");
            }
            rate.setMorningDiscountPct(morningDiscountPct);
        }
        if (morningStart != null) {
            rate.setMorningStart(morningStart);
        }
        if (morningEnd != null) {
            rate.setMorningEnd(morningEnd);
        }
        if (!rate.getMorningStart().isBefore(rate.getMorningEnd())) {
            throw ValidationFailedException.onField("morningEnd",
                    "morningEnd must be after morningStart; set morningDiscountPct to 0 to "
                            + "switch the morning rate off");
        }
        log.info("pricing {} set to {}/hour, {}/block, morning -{}% {}-{}", consoleType,
                rate.getPerHour(), rate.getPerHalfHour(), rate.getMorningDiscountPct(),
                rate.getMorningStart(), rate.getMorningEnd());
        return rate;
    }

    private static void requirePositive(String field, int amount) {
        if (amount <= 0) {
            throw ValidationFailedException.onField(field, field + " must be a positive amount");
        }
    }
}
