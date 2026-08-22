package dev.gamersden.shift.domain;

import dev.gamersden.common.spi.ShiftTakingsLookup;
import dev.gamersden.common.spi.ShiftTakingsLookup.PostedTransaction;
import dev.gamersden.common.spi.ShiftTakingsLookup.Tender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The takings half of the X/Z report: every transaction posted to a shift, folded into the
 * method × category matrix design.md's P2/P3 print.
 *
 * <p>Pure arithmetic over what {@code billing} publishes — no repository, no clock — so the money
 * maths a whole day reconciles against is testable without a database.
 *
 * <p>Three rules decide what lands where:
 *
 * <ol>
 *   <li><strong>Everything posted counts.</strong> No filtering on {@code voided}: a voided sale
 *       and the reversal that undid it are both real postings in the shift, and they cancel out on
 *       their own (invariant §5.7). Dropping either would leave the drawer short or long by the
 *       amount of the void.</li>
 *   <li><strong>Methods come off the splits, categories off the transaction</strong>, and each
 *       tender is attributed to the categories in proportion to what they came to
 *       ({@link ProRata}). A points discount is therefore absorbed pro rata rather than shown as a
 *       category of its own — it is reported separately, as {@code pointsRedeemed}.</li>
 *   <li><strong>Every method gets a row</strong>, in the order {@code billing} names them, even
 *       when nobody paid that way — a report with a missing row reads as a report that forgot to
 *       ask.</li>
 * </ol>
 *
 * @param totals        the bottom line: the sum of every row, so {@code totals.total()} is what was
 *                      tendered across the shift
 * @param saleCount     postings that took money ({@code totalDue >= 0}, so a bill covered entirely
 *                      by points still counts as a sale)
 * @param refundCount   postings that gave it back — refunds and void reversals
 */
public record ShiftTakings(List<MethodTakings> byMethod,
                           MethodTakings totals,
                           int pointsRedeemed,
                           int pointsEarned,
                           int saleCount,
                           int refundCount) {

    private static final int COLUMNS = 5;
    private static final int TOTAL_COLUMN = 4;

    public ShiftTakings {
        byMethod = List.copyOf(byMethod);
    }

    public static ShiftTakings of(List<PostedTransaction> posted, List<String> methods) {
        Map<String, int[]> rows = new LinkedHashMap<>();
        methods.forEach(method -> rows.put(method, new int[COLUMNS]));

        int pointsRedeemed = 0;
        int pointsEarned = 0;
        int sales = 0;
        int refunds = 0;
        for (PostedTransaction tx : posted) {
            pointsRedeemed += tx.pointsRedeemed();
            pointsEarned += tx.pointsEarned();
            if (tx.totalDue() < 0) {
                refunds++;
            } else {
                sales++;
            }
            for (Tender tender : tx.tenders()) {
                attribute(rows.computeIfAbsent(tender.method(), key -> new int[COLUMNS]), tx, tender);
            }
        }

        List<MethodTakings> byMethod = new ArrayList<>(rows.size());
        int[] totals = new int[COLUMNS];
        rows.forEach((method, columns) -> {
            byMethod.add(MethodTakings.of(method, columns));
            for (int i = 0; i < COLUMNS; i++) {
                totals[i] += columns[i];
            }
        });
        return new ShiftTakings(byMethod, MethodTakings.of(MethodTakings.TOTAL, totals),
                pointsRedeemed, pointsEarned, sales, refunds);
    }

    /** The tender's own amount into the method's total, its pro-rata shares into the categories. */
    private static void attribute(int[] row, PostedTransaction tx, Tender tender) {
        int[] shares = ProRata.across(tender.amount(),
                tx.gaming(), tx.fnb(), tx.tournament(), tx.booking());
        for (int i = 0; i < shares.length; i++) {
            row[i] += shares[i];
        }
        row[TOTAL_COLUMN] += tender.amount();
    }

    /** What went into the drawer — the figure a close counts the cash against. */
    public int cash() {
        return byMethod.stream()
                .filter(row -> ShiftTakingsLookup.CASH.equals(row.method()))
                .mapToInt(MethodTakings::total)
                .sum();
    }
}
