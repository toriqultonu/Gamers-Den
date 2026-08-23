package dev.gamersden.report.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ValidationFailedException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * A closed range of <em>venue</em> days, and the two instants that bound it.
 *
 * <p>Every report is asked for in days and answered from timestamps, and the translation between
 * them is the only place a timezone appears: a day runs 00:00 to 24:00 in {@code Asia/Dhaka}, so a
 * sale at 01:30 belongs to the day the venue was still working and not to UTC's next one
 * (ARCHITECTURE.md §5.1, §5.10). The bounds are half-open — {@code [startsAt, endsAt)} — so
 * consecutive windows tile without counting the midnight instant twice.
 *
 * @param from first day, inclusive
 * @param to   last day, inclusive
 */
public record ReportWindow(LocalDate from, LocalDate to) {

    /** S9's revenue trend is 14 bars wide (design.md S9), so an unqualified request asks for 14. */
    public static final int DEFAULT_DAYS = 14;

    /** S2's revenue trend is 30 bars wide (design.md S2). */
    public static final int OVERVIEW_DAYS = 30;

    /**
     * A year and a day. Not a query limit — the aggregates are grouped in Postgres — but a limit
     * on what this answers with: the trend is one entry per day, zero-filled, and a decade-wide
     * request would be a denial of service written as a date.
     */
    public static final int MAX_DAYS = 366;

    public ReportWindow {
        if (from == null || to == null) {
            throw ValidationFailedException.onField("from", "from and to are both required");
        }
        if (to.isBefore(from)) {
            throw ValidationFailedException.onField("to", "to cannot be before from");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ValidationFailedException.onField("from",
                    "a report covers at most %d days".formatted(MAX_DAYS));
        }
    }

    /** The {@code days}-long window ending today, in venue time — what both screens default to. */
    public static ReportWindow endingToday(Clock clock, int days) {
        LocalDate today = VenueTime.businessDay(clock);
        return new ReportWindow(today.minusDays(days - 1L), today);
    }

    /** The equally long window immediately before this one — what "+11% on the previous 30" is of. */
    public ReportWindow previous() {
        long days = days();
        return new ReportWindow(from.minusDays(days), from.minusDays(1));
    }

    public int days() {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** Midnight at the start of {@code from}, venue time — inclusive. */
    public OffsetDateTime startsAt() {
        return from.atStartOfDay(VenueTime.ZONE).toOffsetDateTime();
    }

    /** Midnight at the end of {@code to}, venue time — exclusive. */
    public OffsetDateTime endsAt() {
        return to.plusDays(1).atStartOfDay(VenueTime.ZONE).toOffsetDateTime();
    }

    /**
     * The upper bound for anything measured against elapsed time — occupancy, trading hours —
     * which is the end of the window or now, whichever comes first. A window running to the end of
     * today is not eight hours of idle consoles; those hours have not happened yet.
     */
    public OffsetDateTime elapsedEnd(OffsetDateTime now) {
        OffsetDateTime end = endsAt();
        return now.isBefore(end) ? now : end;
    }

    /** Every day in the window, in order — the spine the trend arrays are zero-filled onto. */
    public List<LocalDate> dates() {
        return Stream.iterate(from, day -> !day.isAfter(to), day -> day.plusDays(1)).toList();
    }
}
