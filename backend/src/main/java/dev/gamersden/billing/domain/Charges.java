package dev.gamersden.billing.domain;

/**
 * The four buckets a transaction snapshot keeps apart ({@code gaming_amount}, {@code fnb_amount},
 * {@code tournament_amount}, {@code booking_amount}) and the X/Z report adds up by the same names
 * (invariant §5.7). Keeping them separate through the settle is what makes reconciliation
 * structural rather than a reconstruction from lines.
 *
 * <p>Gross figures: a points discount is recorded on the transaction as {@code points_redeemed},
 * not by shaving the buckets, so the report can always answer "what did gaming take today" without
 * loyalty muddying it.
 *
 * @param booking bookings and play tickets (B15/B16); always 0 on a floor settle
 */
public record Charges(int gaming, int fnb, int tournament, int booking) {

    public static Charges gaming(int amount) {
        return new Charges(amount, 0, 0, 0);
    }

    public static Charges fnb(int amount) {
        return new Charges(0, amount, 0, 0);
    }

    /** What the buckets come to before any points discount. */
    public int gross() {
        return gaming + fnb + tournament + booking;
    }
}
