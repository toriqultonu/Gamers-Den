package dev.gamersden.common.events;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The one door a domain service uses to say something moved (ARCHITECTURE.md §4.5: "emitted from
 * services after commit via the common SSE hub").
 *
 * <p>Nothing is sent from here. These are Spring application events, and every listener is an
 * {@code @TransactionalEventListener(AFTER_COMMIT)} in the owning package's {@code web/} — so a
 * transaction that rolls back tells the floor nothing, which is the whole point of emitting after
 * commit rather than at the call. A caller therefore announces freely: if the work does not
 * survive, neither does the announcement.
 *
 * <p>Typed methods rather than a raw {@link ApplicationEventPublisher} so that a service names the
 * fact ({@code stationChanged}) instead of constructing wire concerns, and so the set of facts
 * stays enumerable — {@link LiveChange} is sealed.
 */
@Component
public class LiveEvents {

    private final ApplicationEventPublisher publisher;

    public LiveEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** A session, clock, block ledger or match on this console moved. */
    public void stationChanged(long stationId) {
        publisher.publishEvent(new LiveChange.StationChanged(stationId));
    }

    /** A daily token was issued, seated or refunded. */
    public void queueChanged() {
        publisher.publishEvent(new LiveChange.QueueChanged());
    }

    public void bookingChanged(long bookingId) {
        publisher.publishEvent(new LiveChange.BookingChanged(bookingId));
    }

    public void tournamentChanged(long tournamentId) {
        publisher.publishEvent(new LiveChange.TournamentChanged(tournamentId));
    }

    public void alertRaised(long alertId) {
        publisher.publishEvent(new LiveChange.AlertRaised(alertId));
    }

    public void printerStatusChanged() {
        publisher.publishEvent(new LiveChange.PrinterStatusChanged());
    }

    public void syncStatusChanged() {
        publisher.publishEvent(new LiveChange.SyncStatusChanged());
    }
}
