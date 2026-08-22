package dev.gamersden.billing.domain;

import dev.gamersden.billing.repo.PaymentSplitRepository;
import dev.gamersden.billing.repo.TransactionRepository;
import dev.gamersden.common.spi.ShiftTakingsLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code billing} package's answer to {@link ShiftTakingsLookup} — the only door {@code shift}
 * uses into {@code transactions} and {@code payment_splits} (ARCHITECTURE.md §3).
 *
 * <p>It publishes postings rather than sums. The X/Z report is a <em>method × category</em> matrix
 * whose axes live on the two tables read here, and reconciling them is the shift package's
 * arithmetic (B11) — so what this service owes it is the rows, in two queries rather than one per
 * transaction.
 *
 * <p>Nothing is filtered. Voided sales and their reversals are both real postings and both belong
 * to the shift that will be counted against them (invariant §5.7); dropping either one would make
 * a shift with a void reconcile to the wrong drawer.
 */
@Service
public class TakingsLookupService implements ShiftTakingsLookup {

    private final TransactionRepository transactions;
    private final PaymentSplitRepository splits;

    public TakingsLookupService(TransactionRepository transactions, PaymentSplitRepository splits) {
        this.transactions = transactions;
        this.splits = splits;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostedTransaction> transactionsOf(long shiftId) {
        List<Transaction> posted = transactions.findByShiftIdOrderByIdAsc(shiftId);
        if (posted.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Tender>> tenders = splits
                .findByTxIdInOrderByIdAsc(posted.stream().map(Transaction::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(PaymentSplit::getTxId,
                        Collectors.mapping(
                                split -> new Tender(split.getMethod().name(), split.getAmount()),
                                Collectors.toList())));
        return posted.stream()
                .map(tx -> new PostedTransaction(tx.getId(), tx.getPublicId(), tx.getGamingAmount(),
                        tx.getFnbAmount(), tx.getTournamentAmount(), tx.getBookingAmount(),
                        tx.getPointsRedeemed(), tx.getPointsEarned(), tx.getTotalDue(),
                        tx.isVoided(), tenders.getOrDefault(tx.getId(), List.of()))) 
                .toList();
    }

    @Override
    public List<String> tenderMethods() {
        return Arrays.stream(PaymentMethod.values()).map(Enum::name).toList();
    }
}
