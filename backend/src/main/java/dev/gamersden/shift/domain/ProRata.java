package dev.gamersden.shift.domain;

import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Splits one integer across weighted buckets without losing or inventing a taka.
 *
 * <p>The X/Z report needs this because its two axes do not live on the same table: a transaction
 * records <em>what</em> was sold in four buckets, its splits record <em>how</em> it was paid, and
 * design.md's P2 asks for the matrix of the two. Nothing in the schema says which taka of a
 * ৳300 cash tender paid for the drink and which paid for the half hour, so the report attributes
 * each tender to the buckets in proportion to what they came to.
 *
 * <p>Largest-remainder rather than plain rounding: every share is truncated toward zero first and
 * the leftover units are handed out to the buckets that lost the most in the truncation. That way
 * the row always adds back up to the tender exactly, which is what makes the matrix's column
 * totals equal the drawer instead of drifting a taka per receipt.
 *
 * <p>Signs are carried through untouched, so a refund's negative tender allocates to negative
 * buckets and a shift with a void reconciles to the same figures as a shift without one.
 */
final class ProRata {

    private ProRata() {
    }

    /**
     * @return one share per weight, summing exactly to {@code amount}; all zeroes when the weights
     *         cancel out or there is nothing to split
     */
    static int[] across(int amount, int... weights) {
        int[] shares = new int[weights.length];
        long total = 0;
        for (int weight : weights) {
            total += weight;
        }
        if (total == 0 || amount == 0) {
            return shares;
        }

        long[] numerators = new long[weights.length];
        long allocated = 0;
        for (int i = 0; i < weights.length; i++) {
            numerators[i] = (long) amount * weights[i];
            shares[i] = (int) (numerators[i] / total);
            allocated += shares[i];
        }

        long leftover = amount - allocated;
        if (leftover == 0) {
            return shares;
        }
        int[] order = byTruncationLoss(numerators, shares, total);
        int step = leftover > 0 ? 1 : -1;
        for (long handed = 0; handed < Math.abs(leftover); handed++) {
            shares[order[(int) (handed % order.length)]] += step;
        }
        return shares;
    }

    /** The buckets that lost the most to truncation, largest loss first; ties by position. */
    private static int[] byTruncationLoss(long[] numerators, int[] shares, long total) {
        return IntStream.range(0, shares.length)
                .boxed()
                .sorted(Comparator
                        .comparingLong((Integer i) -> Math.abs(numerators[i] - (long) shares[i] * total))
                        .reversed()
                        .thenComparingInt(i -> i))
                .mapToInt(Integer::intValue)
                .toArray();
    }
}
