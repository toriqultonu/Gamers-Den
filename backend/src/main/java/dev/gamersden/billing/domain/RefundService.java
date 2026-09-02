package dev.gamersden.billing.domain;

import dev.gamersden.billing.repo.PaymentSplitRepository;
import dev.gamersden.billing.repo.TransactionRepository;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.StaffPrincipal;
import dev.gamersden.common.spi.MemberSettlement;
import dev.gamersden.common.spi.SaleRefunding;
import dev.gamersden.common.spi.ShiftLookup;
import dev.gamersden.common.spi.SyncOutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * The {@code billing} package's answer to {@link SaleRefunding} — handing back one bucket of a
 * sale that otherwise stands (ARCHITECTURE.md §3). A cancelled tournament's entry fees today, a
 * booking cancelled inside its cutoff from B15.
 *
 * <p>Three rules, all of them invariant §5.7 read closely.
 *
 * <p><strong>A refund is a transaction, not an edit.</strong> The sale row is left exactly as it
 * was printed; the money goes back out as its own negative row, posted to the shift doing the
 * refunding, so a drawer is counted against what happened in it rather than against what was later
 * decided about it.
 *
 * <p><strong>Money goes back the way it came.</strong> The refund's tenders mirror the original
 * sale's methods, scaled to the amount being returned — a bKash entry fee is refunded to bKash,
 * cash to cash. Largest-remainder apportionment keeps the rows summing to the refund exactly, and
 * a method whose share rounds to nothing is dropped rather than written as a zero row that
 * {@code payment_splits CHECK (amount <> 0)} would refuse anyway.
 *
 * <p><strong>Wallet money is not cash.</strong> A share paid from the wallet is credited straight
 * back to the member's balance through the {@code member} package's own door, with a
 * {@code REVERSAL} ledger row against this refund. Points are deliberately untouched: the customer
 * keeps what the sale earned them, because clawing back points they may already have spent would
 * fail an entire cancel over one loyalty balance.
 *
 * <p>{@link Propagation#MANDATORY}: the refund belongs to the decision that caused it, so a cancel
 * that rolls back cannot leave money handed out.
 */
@Service
public class RefundService implements SaleRefunding {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final TransactionRepository transactions;
    private final PaymentSplitRepository splits;
    private final PublicIdSequence publicIds;
    private final MemberSettlement memberWrites;
    private final ShiftLookup shifts;
    private final SyncOutboxWriter outbox;
    private final Clock clock;

    public RefundService(TransactionRepository transactions,
                         PaymentSplitRepository splits,
                         PublicIdSequence publicIds,
                         MemberSettlement memberWrites,
                         ShiftLookup shifts,
                         SyncOutboxWriter outbox,
                         Clock clock) {
        this.transactions = transactions;
        this.splits = splits;
        this.publicIds = publicIds;
        this.memberWrites = memberWrites;
        this.shifts = shifts;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund refund(RefundRequest request) {
        StaffPrincipal staff = CurrentStaff.require();
        long shiftId = requireOpenShift(staff);
        Transaction original = transactions.findById(request.originalTxId())
                .orElseThrow(() -> new NotFoundException("Transaction", request.originalTxId()));
        int amount = requireWithinBucket(original, request);

        Transaction refund = transactions.saveAndFlush(
                negativeOf(original, amount, request.bucket(), shiftId, staff));
        List<Share> back = apportion(splits.findByTxId(original.getId()), amount);
        back.forEach(share -> splits.save(new PaymentSplit(refund.getId(), share.method(),
                -share.amount(), share.paymentRef())));

        int walletBack = back.stream()
                .filter(share -> share.method() == PaymentMethod.WALLET)
                .mapToInt(Share::amount)
                .sum();
        if (walletBack > 0 && original.getMemberId() != null) {
            memberWrites.reverseSale(original.getMemberId(), refund.getId(),
                    new MemberSettlement.LoyaltyMovement(0, 0, walletBack));
        }

        outbox.record(SyncOutboxWriter.TRANSACTIONS, SyncOutboxWriter.REFUNDED, refund.getId(),
                SyncOutboxWriter.data(
                        "publicId", refund.getPublicId(),
                        "shiftId", shiftId,
                        "staffId", staff.id(),
                        "refundOf", original.getId(),
                        "refundOfPublicId", original.getPublicId(),
                        "bucket", request.bucket().name(),
                        "reason", request.reason(),
                        "memberId", refund.getMemberId(),
                        "totalDue", refund.getTotalDue()));
        log.info("refund {} ({}) returns {} BDT of the {} on transaction {} ({}) to shift {} by "
                        + "staff {}: \"{}\"", refund.getId(), refund.getPublicId(), amount,
                request.bucket(), original.getId(), original.getPublicId(), shiftId, staff.id(),
                request.reason());
        return new Refund(refund.getId(), refund.getPublicId(), refund.getTotalDue());
    }

    /**
     * The mirror row: negative total, negative bucket, everything else left off. The loyalty
     * columns stay at zero because the points are not being reversed, and the session and cart
     * links stay off because this refund is not undoing the time or the food on the same receipt.
     */
    private Transaction negativeOf(Transaction original, int amount, Bucket bucket, long shiftId,
                                   StaffPrincipal staff) {
        Transaction refund = new Transaction(publicIds.next(VenueTime.now(clock)), shiftId,
                staff.id(), -amount);
        refund.setMemberId(original.getMemberId());
        switch (bucket) {
            case GAMING -> refund.setGamingAmount(-amount);
            case FNB -> refund.setFnbAmount(-amount);
            case TOURNAMENT -> refund.setTournamentAmount(-amount);
            case BOOKING -> refund.setBookingAmount(-amount);
        }
        return refund;
    }

    /**
     * A refund can never hand back more than that bucket of that sale ever took. It is a ceiling,
     * not a running balance — nothing in the schema links a refund back to the sale it came from,
     * so "has this already been refunded?" is the caller's flag to keep (a tournament entry's
     * {@code refunded} column), and this is the backstop that catches the arithmetic going wrong.
     */
    private static int requireWithinBucket(Transaction original, RefundRequest request) {
        if (request.amount() <= 0) {
            throw new IllegalArgumentException("A refund returns a positive amount");
        }
        if (original.getTotalDue() < 0) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Transaction %s is itself a refund".formatted(original.getPublicId()),
                    Map.of("transactionId", original.getId()));
        }
        int taken = bucketOf(original, request.bucket());
        if (request.amount() > taken) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Transaction %s only took %d BDT of %s — it cannot refund %d"
                            .formatted(original.getPublicId(), taken, request.bucket(),
                                    request.amount()),
                    Map.of("transactionId", original.getId(), "available", taken,
                            "requested", request.amount()));
        }
        return request.amount();
    }

    private static int bucketOf(Transaction tx, Bucket bucket) {
        return switch (bucket) {
            case GAMING -> tx.getGamingAmount();
            case FNB -> tx.getFnbAmount();
            case TOURNAMENT -> tx.getTournamentAmount();
            case BOOKING -> tx.getBookingAmount();
        };
    }

    /**
     * Splits {@code amount} across the sale's tenders in the proportion they were taken in. Each
     * method gets the floor of its share and the leftover pennies go to the largest fractions
     * first, so the parts add up to the whole exactly and the biggest tender absorbs the rounding.
     *
     * <p>A sale settled entirely with points has no tenders at all; the refund then has none
     * either, which is correct — no money came in, so none goes out, and the negative transaction
     * still records that the charge stopped standing.
     */
    private static List<Share> apportion(List<PaymentSplit> tendered, int amount) {
        int total = tendered.stream().mapToInt(PaymentSplit::getAmount).sum();
        if (total <= 0) {
            return List.of();
        }
        List<Share> shares = new ArrayList<>(tendered.size());
        int allocated = 0;
        for (PaymentSplit split : tendered) {
            long exact = (long) amount * split.getAmount();
            int share = (int) (exact / total);
            allocated += share;
            shares.add(new Share(split.getMethod(), share, split.getPaymentRef(),
                    (int) (exact % total)));
        }
        // Each floor loses less than 1, so there are never more leftovers than there are rows.
        Integer[] byRemainder = IntStream.range(0, shares.size()).boxed().toArray(Integer[]::new);
        Arrays.sort(byRemainder,
                Comparator.comparingInt((Integer i) -> shares.get(i).remainder()).reversed());
        for (int i = 0; i < amount - allocated; i++) {
            shares.set(byRemainder[i], shares.get(byRemainder[i]).plusOne());
        }
        return shares.stream().filter(share -> share.amount() > 0).toList();
    }

    private long requireOpenShift(StaffPrincipal staff) {
        return shifts.openShiftId(staff.terminal())
                .orElseThrow(() -> new ConflictException(ErrorCode.CONFLICT,
                        "No shift is open on %s — a refund has to land in a counted drawer"
                                .formatted(staff.terminal()),
                        Map.of("terminal", staff.terminal())));
    }

    /** One method's share of a refund, plus the fraction it was rounded down by. */
    private record Share(PaymentMethod method, int amount, String paymentRef, int remainder) {

        Share plusOne() {
            return new Share(method, amount + 1, paymentRef, remainder);
        }
    }
}
