package dev.gamersden.billing.domain;

import dev.gamersden.common.error.ValidationFailedException;

import java.util.Arrays;
import java.util.Locale;

/** {@code payment_splits.method}. */
public enum PaymentMethod {
    CASH,
    BKASH,
    NAGAD,
    WALLET;

    /**
     * Parses a method that arrived as a string rather than through bean validation — the
     * {@code common.spi} doors deliberately keep this enum out of their signatures, so somebody
     * has to turn the name back into it here. An unknown one is the same 400 Jackson would have
     * produced, with the choices spelled out.
     */
    public static PaymentMethod parse(String method) {
        if (method == null || method.isBlank()) {
            throw ValidationFailedException.onField("method", "Every tender needs a method");
        }
        try {
            return valueOf(method.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw ValidationFailedException.onField("method",
                    "\"%s\" is not a payment method — one of %s"
                            .formatted(method, Arrays.toString(values())));
        }
    }
}
