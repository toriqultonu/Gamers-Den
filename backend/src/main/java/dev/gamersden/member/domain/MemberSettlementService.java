package dev.gamersden.member.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.MemberSettlement;
import dev.gamersden.member.repo.MemberRepository;
import dev.gamersden.member.repo.PointsLedgerRepository;
import dev.gamersden.member.repo.WalletLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * The {@code member} package's answer to {@link MemberSettlement} — the loyalty half of
 * {@code POST /payments} and {@code POST /payments/{id}/void} (ARCHITECTURE.md §3).
 *
 * <p>It keeps B08's rule unchanged: the ledger is the source of truth, {@code members.points} and
 * {@code members.wallet} are its running totals, the row is locked before either moves, and both
 * move in the same transaction. What is new is that the transaction is now someone else's —
 * {@link Propagation#MANDATORY} makes that structural, because points earned on a settle that
 * rolled back are points nobody paid for (invariant §5.3).
 *
 * <p>Every ledger row written here carries {@code ref_tx_id}, so the member's history reads back
 * as "this sale gave you 14 points" rather than a floating adjustment (invariant §5.7).
 */
@Service
public class MemberSettlementService implements MemberSettlement {

    private static final Logger log = LoggerFactory.getLogger(MemberSettlementService.class);

    private final MemberRepository members;
    private final PointsLedgerRepository pointsLedger;
    private final WalletLedgerRepository walletLedger;

    public MemberSettlementService(MemberRepository members,
                                   PointsLedgerRepository pointsLedger,
                                   WalletLedgerRepository walletLedger) {
        this.members = members;
        this.pointsLedger = pointsLedger;
        this.walletLedger = walletLedger;
    }

    /**
     * Points out, points in, wallet down — in that order, so the redemption a customer just made
     * cannot be paid for out of the points the same sale is about to earn.
     *
     * <p>The wallet floor is decided here rather than on the quoted bill: the bill's figure is a
     * read from before the lock, and between the two a top-up or another terminal's settle may
     * have moved it. 409 {@code WALLET_INSUFFICIENT} against the locked balance is the truth.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applySale(long memberId, long txId, LoyaltyMovement movement) {
        if (movement.isEmpty()) {
            return;
        }
        Member member = lock(memberId);
        if (movement.pointsRedeemed() > member.getPoints()) {
            throw new ConflictException(ErrorCode.INSUFFICIENT_POINTS,
                    "Member %d has %d points, cannot redeem %d"
                            .formatted(memberId, member.getPoints(), movement.pointsRedeemed()),
                    Map.of("points", member.getPoints(), "requested", movement.pointsRedeemed()));
        }
        if (movement.walletSpent() > member.getWallet()) {
            throw new ConflictException(ErrorCode.WALLET_INSUFFICIENT,
                    "Member %d has %d BDT in the wallet, cannot spend %d"
                            .formatted(memberId, member.getWallet(), movement.walletSpent()),
                    Map.of("wallet", member.getWallet(), "requested", movement.walletSpent()));
        }

        if (movement.pointsRedeemed() > 0) {
            pointsLedger.save(new PointsLedgerEntry(memberId, -movement.pointsRedeemed(),
                    PointsKind.REDEEM_BILL, txId));
            member.setPoints(member.getPoints() - movement.pointsRedeemed());
        }
        if (movement.pointsEarned() > 0) {
            pointsLedger.save(new PointsLedgerEntry(memberId, movement.pointsEarned(),
                    PointsKind.EARN, txId));
            member.setPoints(member.getPoints() + movement.pointsEarned());
        }
        if (movement.walletSpent() > 0) {
            walletLedger.save(new WalletLedgerEntry(memberId, -movement.walletSpent(),
                    WalletKind.SPEND, txId));
            member.setWallet(member.getWallet() - movement.walletSpent());
        }
        log.info("member {} on transaction {}: -{} pts redeemed, +{} pts earned, -{} wallet "
                        + "-> wallet {} points {}", memberId, txId, movement.pointsRedeemed(),
                movement.pointsEarned(), movement.walletSpent(), member.getWallet(), member.getPoints());
    }

    /**
     * The mirror image, as {@code REVERSAL} rows against the reversal transaction. Reversal is a
     * new pair of ledger entries rather than a deletion — the history of a voided sale stays
     * readable, which is what makes the void auditable (invariant §5.4).
     *
     * <p>One case can refuse: the member has already spent the points the sale earned. Clamping
     * the column at zero would leave it disagreeing with its own ledger, so this is 409
     * {@code INSUFFICIENT_POINTS} and the whole void rolls back.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseSale(long memberId, long reversalTxId, LoyaltyMovement movement) {
        if (movement.isEmpty()) {
            return;
        }
        Member member = lock(memberId);
        if (movement.pointsEarned() > member.getPoints() + movement.pointsRedeemed()) {
            throw new ConflictException(ErrorCode.INSUFFICIENT_POINTS,
                    "Member %d has already spent the %d points this sale earned — the void would "
                            + "leave a negative balance".formatted(memberId, movement.pointsEarned()),
                    Map.of("points", member.getPoints(), "pointsEarned", movement.pointsEarned()));
        }

        if (movement.pointsRedeemed() > 0) {
            pointsLedger.save(new PointsLedgerEntry(memberId, movement.pointsRedeemed(),
                    PointsKind.REVERSAL, reversalTxId));
            member.setPoints(member.getPoints() + movement.pointsRedeemed());
        }
        if (movement.pointsEarned() > 0) {
            pointsLedger.save(new PointsLedgerEntry(memberId, -movement.pointsEarned(),
                    PointsKind.REVERSAL, reversalTxId));
            member.setPoints(member.getPoints() - movement.pointsEarned());
        }
        if (movement.walletSpent() > 0) {
            walletLedger.save(new WalletLedgerEntry(memberId, movement.walletSpent(),
                    WalletKind.REVERSAL, reversalTxId));
            member.setWallet(member.getWallet() + movement.walletSpent());
        }
        log.info("member {} reversed on transaction {}: +{} pts back, -{} pts unearned, +{} wallet "
                        + "-> wallet {} points {}", memberId, reversalTxId, movement.pointsRedeemed(),
                movement.pointsEarned(), movement.walletSpent(), member.getWallet(), member.getPoints());
    }

    private Member lock(long memberId) {
        return members.findByIdForUpdate(memberId)
                .orElseThrow(() -> new NotFoundException("Member", memberId));
    }
}
