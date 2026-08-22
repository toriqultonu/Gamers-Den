package dev.gamersden.shift.domain;

/**
 * The drawer arithmetic, which is the whole point of a Z (design.md P2 — "expected vs counted vs
 * discrepancy", double-height).
 *
 * <pre>{@code expected = openingFloat + cash takings - expenses}</pre>
 *
 * <p>Cash takings, not takings: bKash, Nagad and wallet never touch the till, and refunds are
 * already negative cash rows, so they subtract themselves. Expenses come straight back out of the
 * drawer. Nothing here is stored while the shift is open — expected cash is derived, and only the
 * close writes its snapshot (invariant §5.4).
 *
 * @param counted      what the operator counted; {@code null} on an interim X, which deliberately
 *                     omits the drawer
 * @param discrepancy  {@code counted - expected} — positive is over, negative is short
 */
public record CashCount(int openingFloat, int takings, int expenses, int expected,
                        Integer counted, Integer discrepancy) {

    /** The X: what should be in the drawer, without asking anyone to count it. */
    static CashCount interim(int openingFloat, int cashTakings, int expenses) {
        return new CashCount(openingFloat, cashTakings, expenses,
                expected(openingFloat, cashTakings, expenses), null, null);
    }

    /** The Z: the same figure, against the count. */
    static CashCount counted(int openingFloat, int cashTakings, int expenses, int countedCash) {
        int expected = expected(openingFloat, cashTakings, expenses);
        return new CashCount(openingFloat, cashTakings, expenses, expected, countedCash,
                countedCash - expected);
    }

    private static int expected(int openingFloat, int cashTakings, int expenses) {
        return openingFloat + cashTakings - expenses;
    }
}
