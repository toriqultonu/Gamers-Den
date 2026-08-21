package dev.gamersden.member.domain;

import dev.gamersden.common.spi.MemberPointsLookup;
import dev.gamersden.member.repo.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The {@code member} package's answer to {@link MemberPointsLookup} — the only door
 * {@code billing} uses into {@code members} (ARCHITECTURE.md §3).
 *
 * <p>Read-only. The balances themselves only ever move through {@link WalletService}, so the bill
 * can quote {@code pointsRedeemable} without any risk of spending anything.
 */
@Service
public class MemberLoyaltyLookupService implements MemberPointsLookup {

    private final MemberRepository members;

    public MemberLoyaltyLookupService(MemberRepository members) {
        this.members = members;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Loyalty> loyaltyOf(long memberId) {
        return members.findById(memberId)
                .map(member -> new Loyalty(member.getId(), member.getName(),
                        member.getPoints(), member.getWallet()));
    }
}
