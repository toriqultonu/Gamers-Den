package dev.gamersden.member.domain;

import dev.gamersden.common.spi.MemberBookingLookup;

import java.util.List;

/**
 * {@code GET /members/{id}} — the member, their recent visits and their recent bookings. Both
 * strips are assembled from other packages' lookups; the {@code member} package reads neither
 * {@code sessions} nor {@code bookings} itself (ARCHITECTURE.md §3).
 */
public record MemberProfile(Member member,
                            List<MemberVisit> visits,
                            List<MemberBookingLookup.MemberBooking> bookings) {
}
