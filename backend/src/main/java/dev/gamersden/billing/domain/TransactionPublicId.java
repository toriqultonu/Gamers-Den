package dev.gamersden.billing.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * {@code transactions.public_id} — the id printed and barcoded on every receipt,
 * {@code GD-2608-047} (V001). Day and month, then that day's running number: short enough to read
 * back over the phone, unique enough to be the UNIQUE column it is.
 *
 * <p>Pure formatting, so the shape is unit-testable; allocating the number is
 * {@link PaymentService}'s job, under a lock, because two terminals settling in the same second
 * must not both be told they are number 47.
 */
public final class TransactionPublicId {

    public static final String PREFIX = "GD-";

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("ddMM");

    private TransactionPublicId() {
    }

    /** Everything before the sequence — also the {@code LIKE} pattern the day's count runs on. */
    public static String dayPrefix(LocalDate day) {
        return PREFIX + DAY_MONTH.format(day) + "-";
    }

    /**
     * @param sequence 1-based within the venue day; past 999 the number simply grows a digit
     *                 rather than wrapping, because a collision would be worse than a wide id
     */
    public static String of(LocalDate day, long sequence) {
        return dayPrefix(day) + "%03d".formatted(sequence);
    }
}
