package dev.gamersden.shift.domain;

/**
 * One row of the X/Z takings matrix: what a tender method took, split across the categories it
 * paid for (design.md P2 — "takings method × (gaming, F&amp;B, tournament, pre-booking, total)").
 *
 * <p>{@code total} is the authoritative figure: it is the sum of that method's tenders, straight
 * off {@code payment_splits}. The four category columns are that total attributed by
 * {@link ProRata}, so they add back up to it — and, in the same breath, absorb the points discount
 * pro rata, since a discounted sale tenders less than its buckets come to.
 */
public record MethodTakings(String method, int gaming, int fnb, int tournament, int booking,
                            int total) {

    /** The summary row's method name — the matrix's bottom line. */
    public static final String TOTAL = "TOTAL";

    static MethodTakings of(String method, int[] columns) {
        return new MethodTakings(method, columns[0], columns[1], columns[2], columns[3], columns[4]);
    }

    public boolean isEmpty() {
        return gaming == 0 && fnb == 0 && tournament == 0 && booking == 0 && total == 0;
    }
}
