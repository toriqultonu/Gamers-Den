package dev.gamersden.member.web;

import dev.gamersden.member.domain.Member;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One member on the wire — the S6 table row, the search result, and what a wallet movement returns
 * so the dialog can show the new balance without a second call.
 *
 * @param wallet integer BDT, never negative
 * @param points 1 point = ৳1 when redeemed
 */
@Schema(name = "Member", description = "A registered customer with their wallet and points balance")
public record MemberView(long id,
                         String name,
                         String phone,
                         String preferredConsole,
                         List<String> games,
                         int wallet,
                         int points,
                         OffsetDateTime createdAt) {

    public static MemberView of(Member member) {
        return new MemberView(member.getId(), member.getName(), member.getPhone(),
                member.getPreferredConsole(),
                member.getGames() == null ? List.of() : List.of(member.getGames()),
                member.getWallet(), member.getPoints(), member.getCreatedAt());
    }
}
