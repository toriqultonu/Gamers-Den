package dev.gamersden.common.util;

/**
 * Money is integer BDT everywhere — no decimals, no BigDecimal (ARCHITECTURE.md §6). These helpers
 * exist so rounding rules live in one place instead of being re-derived per feature.
 */
public final class Money {

    public static final String CURRENCY = "BDT";
    public static final String SYMBOL = "৳";

    private Money() {
    }

    /** Percentage discount, e.g. the morning −25%. Rounds half-up on the discounted amount. */
    public static int applyPercentDiscount(int amount, int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent out of range: " + percent);
        }
        return Math.toIntExact(Math.round(amount * (100 - percent) / 100.0));
    }

    /** Loyalty earn rule (api-contract.md §1): floor(due / 20) points. */
    public static int pointsEarned(int due) {
        return due <= 0 ? 0 : due / 20;
    }

    /** Redemption is 1 point = ৳1 and can never exceed the bill. */
    public static int cappedRedemption(int points, int total) {
        return Math.max(0, Math.min(points, total));
    }

    /** Display form used by receipts and logs. */
    public static String format(int amount) {
        return SYMBOL + amount;
    }
}
