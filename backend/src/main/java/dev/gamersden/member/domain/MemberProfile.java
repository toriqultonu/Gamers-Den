package dev.gamersden.member.domain;

import java.util.List;

/**
 * {@code GET /members/{id}} — the member plus their recent visits. The bookings list the contract
 * also promises here joins in B15, when {@code bookings} exists.
 */
public record MemberProfile(Member member, List<MemberVisit> visits) {
}
