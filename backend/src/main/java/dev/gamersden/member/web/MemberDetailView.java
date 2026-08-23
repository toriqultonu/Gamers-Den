package dev.gamersden.member.web;

import dev.gamersden.member.domain.MemberProfile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /members/{id}} — the member card plus their recent visits and recent bookings, newest
 * first (api-contract.md, Members).
 */
@Schema(name = "MemberDetail", description = "A member with their recent visits and bookings")
public record MemberDetailView(long id,
                               String name,
                               String phone,
                               String preferredConsole,
                               List<String> games,
                               int wallet,
                               int points,
                               OffsetDateTime createdAt,
                               List<VisitView> visits,
                               List<MemberBookingView> bookings) {

    public static MemberDetailView of(MemberProfile profile) {
        MemberView member = MemberView.of(profile.member());
        return new MemberDetailView(member.id(), member.name(), member.phone(),
                member.preferredConsole(), member.games(), member.wallet(), member.points(),
                member.createdAt(), profile.visits().stream().map(VisitView::of).toList(),
                profile.bookings().stream().map(MemberBookingView::of).toList());
    }
}
