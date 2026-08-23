package dev.gamersden.report.domain;

import dev.gamersden.common.config.VenueTime;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Interval arithmetic for the two S9 tiles that measure time rather than money — station
 * utilisation and busiest hours.
 *
 * <p>A pure function over spans, with no Spring and no database in sight, because that is what
 * makes the awkward cases answerable: a session that started before the window opened, one that
 * has not ended, two tills open at once, a stretch that runs across midnight.
 */
public final class TimeSpans {

    private TimeSpans() {
    }

    /**
     * One half-open stretch of wall-clock time. {@code endsAt} is null for something still
     * running — a live session, an open till — and {@link #clip} is what decides where "still
     * running" stops.
     */
    public record Span(OffsetDateTime startsAt, OffsetDateTime endsAt) {

        public long seconds() {
            return endsAt == null ? 0 : Math.max(0, Duration.between(startsAt, endsAt).getSeconds());
        }
    }

    /**
     * The part of {@code span} inside {@code [from, to)}, or empty when none of it is. An
     * open-ended span is treated as running to {@code to} — which is why callers pass
     * {@code min(windowEnd, now)} and never the raw window end.
     */
    public static Optional<Span> clip(Span span, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime start = span.startsAt().isAfter(from) ? span.startsAt() : from;
        OffsetDateTime end = span.endsAt() == null || span.endsAt().isAfter(to) ? to : span.endsAt();
        return end.isAfter(start) ? Optional.of(new Span(start, end)) : Optional.empty();
    }

    /** Every span clipped to the window, oldest first; the ones that miss it entirely drop out. */
    public static List<Span> clipAll(List<Span> spans, OffsetDateTime from, OffsetDateTime to) {
        return spans.stream()
                .map(span -> clip(span, from, to))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Span::startsAt))
                .toList();
    }

    /**
     * How much time the spans covered between them, counting an instant once however many spans
     * hold it. Trading hours are a union because two terminals open at once is one open venue, not
     * two; a single station's occupancy is a plain sum instead, because the
     * {@code one_live_session_per_station} index (V001) makes overlap there impossible.
     */
    public static long unionSeconds(List<Span> spans) {
        long total = 0;
        OffsetDateTime openFrom = null;
        OffsetDateTime openTo = null;
        for (Span span : spans.stream().sorted(Comparator.comparing(Span::startsAt)).toList()) {
            if (span.endsAt() == null) {
                continue;
            }
            if (openFrom == null) {
                openFrom = span.startsAt();
                openTo = span.endsAt();
            } else if (span.startsAt().isAfter(openTo)) {
                total += Duration.between(openFrom, openTo).getSeconds();
                openFrom = span.startsAt();
                openTo = span.endsAt();
            } else if (span.endsAt().isAfter(openTo)) {
                openTo = span.endsAt();
            }
        }
        return openFrom == null ? total : total + Duration.between(openFrom, openTo).getSeconds();
    }

    /**
     * The spans spread across the 24 venue hours-of-day they occupied, in seconds. A seat busy
     * from 17:40 to 18:10 puts 1200 seconds in hour 17 and 600 in hour 18 — which is what lets
     * "average stations busy this hour" be an average rather than a count of overlaps.
     */
    public static long[] byHourOfDay(List<Span> spans) {
        long[] buckets = new long[24];
        for (Span span : spans) {
            if (span.endsAt() == null) {
                continue;
            }
            ZonedDateTime cursor = span.startsAt().atZoneSameInstant(VenueTime.ZONE);
            ZonedDateTime end = span.endsAt().atZoneSameInstant(VenueTime.ZONE);
            while (cursor.isBefore(end)) {
                ZonedDateTime nextHour = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                ZonedDateTime sliceEnd = nextHour.isBefore(end) ? nextHour : end;
                buckets[cursor.getHour()] += Duration.between(cursor, sliceEnd).getSeconds();
                cursor = sliceEnd;
            }
        }
        return buckets;
    }
}
