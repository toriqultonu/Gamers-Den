package dev.gamersden.member.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.member.repo.MemberRepository;
import dev.gamersden.member.repo.PointsLedgerRepository;
import dev.gamersden.member.repo.WalletLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Wallet and points movements (api-contract.md, "Members, wallet, points"):
 * {@code POST /members/{id}/wallet/topup} and {@code /wallet/redeem-points}, both on the
 * idempotency list (§1) — the filter replays a retry, so a double-tapped top-up credits once.
 *
 * <p>Two rules hold every method here together:
 *
 * <ol>
 *   <li><strong>The ledger is the source of truth.</strong> {@code members.wallet} and
 *       {@code members.points} are running totals of {@code wallet_ledger} / {@code points_ledger}
 *       and are never written without the matching ledger row <em>in the same transaction</em>.
 *       The member row is locked first ({@code findByIdForUpdate}) so two concurrent movements
 *       cannot both read the same total and write it back.</li>
 *   <li><strong>Balances never go negative.</strong> Guarded here with the canonical 409s and
 *       again by the DDL's {@code CHECK (wallet >= 0)} / {@code CHECK (points >= 0)}.</li>
 * </ol>
 *
 * <p>Earning on settle and redeeming against a bill are B10's — they belong to the payment
 * transaction. What is missing until then is the tender: {@code wallet_ledger} has no method or
 * reference column, only {@code ref_tx_id}, so a top-up's {@code method}/{@code paymentRef} is
 * validated and logged but not stored. B10 attaches the transaction that owns it, and with it the
 * X/Z drawer line for cash taken at the counter.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final MemberRepository members;
    private final WalletLedgerRepository walletLedger;
    private final PointsLedgerRepository pointsLedger;

    public WalletService(MemberRepository members, WalletLedgerRepository walletLedger,
                         PointsLedgerRepository pointsLedger) {
        this.members = members;
        this.walletLedger = walletLedger;
        this.pointsLedger = pointsLedger;
    }

    /**
     * Cash (or bKash/Nagad) in, wallet up. One {@code TOPUP} ledger row, one column update, one
     * transaction.
     */
    @Transactional
    public Member topUp(long memberId, int amount, TopupMethod method, String paymentRef) {
        if (amount <= 0) {
            throw ValidationFailedException.onField("amount", "A top-up must be above zero");
        }
        Member member = lock(memberId);
        walletLedger.save(new WalletLedgerEntry(member.getId(), amount, WalletKind.TOPUP, null));
        member.setWallet(member.getWallet() + amount);
        log.info("member {} wallet +{} by {}{} -> {}", member.getId(), amount, method,
                paymentRef == null || paymentRef.isBlank() ? "" : " ref *" + lastFour(paymentRef),
                member.getWallet());
        return member;
    }

    /**
     * Points to wallet at 1 point = ৳1 — two ledgers, one movement: points down
     * ({@code REDEEM_WALLET}), wallet up ({@code POINTS_CONVERSION}). Asking for more points than
     * the member holds is 409 {@code INSUFFICIENT_POINTS} and writes nothing.
     */
    @Transactional
    public Member redeemPointsToWallet(long memberId, int points) {
        if (points <= 0) {
            throw ValidationFailedException.onField("points", "Redeem at least one point");
        }
        Member member = lock(memberId);
        if (points > member.getPoints()) {
            throw new ConflictException(ErrorCode.INSUFFICIENT_POINTS,
                    "Member %d has %d points, cannot redeem %d"
                            .formatted(member.getId(), member.getPoints(), points),
                    Map.of("points", member.getPoints(), "requested", points));
        }
        pointsLedger.save(new PointsLedgerEntry(member.getId(), -points, PointsKind.REDEEM_WALLET, null));
        walletLedger.save(new WalletLedgerEntry(member.getId(), points, WalletKind.POINTS_CONVERSION, null));
        member.setPoints(member.getPoints() - points);
        member.setWallet(member.getWallet() + points);
        log.info("member {} redeemed {} points to wallet -> wallet {} points {}",
                member.getId(), points, member.getWallet(), member.getPoints());
        return member;
    }

    private Member lock(long memberId) {
        return members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new NotFoundException("Member", memberId));
    }

    /** Payment references are logged last-4 only (invariant §5.12). */
    private static String lastFour(String paymentRef) {
        String trimmed = paymentRef.trim();
        return trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4);
    }
}
