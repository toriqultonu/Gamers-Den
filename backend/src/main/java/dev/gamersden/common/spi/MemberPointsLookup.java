package dev.gamersden.common.spi;

import java.util.Optional;

/**
 * The narrow read the {@code billing} package needs from {@code member} — the loyalty balance the
 * bill caps {@code pointsRedeemable} against (api-contract.md §1: redemption at settle is a bill
 * discount capped at {@code min(points, total)}) — without reaching for {@code MemberRepository}
 * (ARCHITECTURE.md §3).
 *
 * <p>Implemented by {@code member/domain/MemberLoyaltyLookupService}. Read-only: earning,
 * redeeming and wallet spend are writes {@code member} keeps to itself, driven from the settle in
 * B10.
 */
public interface MemberPointsLookup {

    /** The member's loyalty balances, or empty when the id is unknown. */
    Optional<Loyalty> loyaltyOf(long memberId);

    /**
     * @param points points on hand — 1 point = ৳1 against a bill
     * @param wallet prepaid wallet in BDT; the settle's wallet floor reads it, the bill only shows it
     */
    record Loyalty(long memberId, String name, int points, int wallet) {
    }
}
