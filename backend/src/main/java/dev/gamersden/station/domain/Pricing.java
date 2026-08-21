package dev.gamersden.station.domain;

import dev.gamersden.common.util.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * {@code pricing} — one row per console type, keyed by the type itself. Prices are integer BDT
 * and are snapshotted onto blocks at purchase, so edits only affect new blocks.
 */
@Entity
@Table(name = "pricing")
public class Pricing {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "console_type", nullable = false)
    private ConsoleType consoleType;

    @Column(name = "per_hour", nullable = false)
    private int perHour;

    @Column(name = "per_half_hour", nullable = false)
    private int perHalfHour;

    /** OPEN FLAG (ARCHITECTURE.md §8): documented defaults until the venue confirms. */
    @Column(name = "morning_discount_pct", nullable = false)
    private int morningDiscountPct = 25;

    @Column(name = "morning_start", nullable = false)
    private LocalTime morningStart = LocalTime.of(10, 0);

    @Column(name = "morning_end", nullable = false)
    private LocalTime morningEnd = LocalTime.of(14, 0);

    /**
     * The DB default stamps the seed insert (the app never inserts a rate row — V001 does), and
     * {@link #touch()} stamps every edit. Deliberately not {@code @Generated}: Hibernate treats a
     * generated property as immutable and would silently drop the new value from the UPDATE.
     */
    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    protected Pricing() {
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public Pricing(ConsoleType consoleType, int perHour, int perHalfHour) {
        this.consoleType = consoleType;
        this.perHour = perHour;
        this.perHalfHour = perHalfHour;
    }

    /**
     * What one 30-minute block costs when bought at this venue-local time. The morning window is
     * half-open — [morningStart, morningEnd) — and discounted; every other hour pays the standard
     * half-hour rate. Callers snapshot the answer onto {@code session_blocks.price}, which is why
     * a later rate edit only ever reaches new blocks.
     */
    public int blockPriceAt(LocalTime venueLocalTime) {
        return isMorning(venueLocalTime)
                ? Money.applyPercentDiscount(perHalfHour, morningDiscountPct)
                : perHalfHour;
    }

    /** OPEN FLAG (ARCHITECTURE.md, open flags): 10:00-14:00 stands until the venue confirms. */
    public boolean isMorning(LocalTime venueLocalTime) {
        return !venueLocalTime.isBefore(morningStart) && venueLocalTime.isBefore(morningEnd);
    }

    public ConsoleType getConsoleType() {
        return consoleType;
    }

    public int getPerHour() {
        return perHour;
    }

    public void setPerHour(int perHour) {
        this.perHour = perHour;
    }

    public int getPerHalfHour() {
        return perHalfHour;
    }

    public void setPerHalfHour(int perHalfHour) {
        this.perHalfHour = perHalfHour;
    }

    public int getMorningDiscountPct() {
        return morningDiscountPct;
    }

    public void setMorningDiscountPct(int morningDiscountPct) {
        this.morningDiscountPct = morningDiscountPct;
    }

    public LocalTime getMorningStart() {
        return morningStart;
    }

    public void setMorningStart(LocalTime morningStart) {
        this.morningStart = morningStart;
    }

    public LocalTime getMorningEnd() {
        return morningEnd;
    }

    public void setMorningEnd(LocalTime morningEnd) {
        this.morningEnd = morningEnd;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
