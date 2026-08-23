package dev.gamersden.billing.domain;

import dev.gamersden.billing.repo.TransactionRepository;
import dev.gamersden.common.spi.RevenueLookup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code billing} package's answer to {@link RevenueLookup} — the only door {@code report}
 * uses into {@code transactions} (ARCHITECTURE.md §3).
 *
 * <p>Every method here is one grouped query and nothing else. A report covers weeks, so the fold
 * belongs in the database: pulling a month of postings into memory to add them up would be the
 * stored-rollup problem in a different disguise (TASKLIST B20).
 */
@Service
public class RevenueLookupService implements RevenueLookup {

    private final TransactionRepository transactions;

    public RevenueLookupService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyRevenue> dailyRevenue(OffsetDateTime from, OffsetDateTime to) {
        return transactions.dailyRevenue(from, to).stream()
                .map(row -> new DailyRevenue(row.getDay(), row.getGaming(), row.getFnb(),
                        row.getTournament(), row.getBooking(), row.getPointsRedeemed(),
                        row.getRevenue(), row.getTransactions(), row.getSales()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HourlyRevenue> hourlyRevenue(OffsetDateTime from, OffsetDateTime to) {
        return transactions.hourlyRevenue(from, to).stream()
                .map(row -> new HourlyRevenue(row.getHour(), row.getRevenue(), row.getSales()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> settledCartIds(OffsetDateTime from, OffsetDateTime to) {
        return transactions.settledCartIds(from, to);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Integer> takingsByShift(Collection<Long> shiftIds) {
        if (shiftIds.isEmpty()) {
            return Map.of();
        }
        return transactions.takingsByShift(shiftIds).stream()
                .collect(Collectors.toMap(TransactionRepository.ShiftTakingsRow::getShiftId,
                        TransactionRepository.ShiftTakingsRow::getRevenue));
    }
}
