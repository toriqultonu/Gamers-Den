package dev.gamersden.common.spi;

/**
 * The narrow write any package needs into {@code alerts} — the operator feed on S2 — without
 * reaching for {@code AlertRepository} (ARCHITECTURE.md §3). Implemented by
 * {@code alert/domain/AlertService}.
 *
 * <p>{@code MANDATORY} on the implementation: an alert states that something happened, so it is
 * written in the transaction that made it happen. A cash discrepancy that survived a rolled-back
 * shift close would be an alert about a shift that is still open.
 */
public interface AlertPublisher {

    /** Cash counted at close did not match what the shift's takings expect (B11). */
    String CASH_DISCREPANCY = "CASH_DISCREPANCY";

    /** An item crossed its reorder point on a sale — the stock watchlist's own alert (B19). */
    String LOW_STOCK = "LOW_STOCK";

    /**
     * Records one alert, in the caller's transaction.
     *
     * @return the {@code alerts.id} that was written
     */
    long raise(String type, String title, String body);
}
