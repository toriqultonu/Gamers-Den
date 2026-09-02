package dev.gamersden.queue.domain;

import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.spi.PlayTicketSettlement;
import dev.gamersden.common.spi.QueueTokenIssuing;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.queue.repo.QueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static dev.gamersden.common.config.VenueTime.now;

/**
 * The {@code queue} package's answer to {@link PlayTicketSettlement} — the row {@code billing}
 * writes through while it is taking the money for a walk-up play ticket (docs/bookings.md §3).
 *
 * <p>Deliberately separate from {@link PlayQueueService}. This bean is what {@code billing} calls
 * <em>into</em> during a sale; {@code PlayQueueService} is what calls <em>out</em> to
 * {@code billing} to sell a standalone ticket and to refund a no-show. Keeping the two directions
 * in different beans is what stops the two packages from forming a construction cycle — the same
 * shape {@code booking} and {@code tournament} both use.
 *
 * <p>Two calls, for the reason {@code tournament} splits its entries the same way: the settle has
 * to know what the tickets cost before it can write the transaction they hang off, and
 * {@code queue_entries.tx_id} is {@code NOT NULL}. {@link #quote} therefore refuses everything
 * refusable — an unknown console type, a length of nothing — before a single row is written, so a
 * 400 leaves the database exactly as it found it.
 *
 * <p>What is <em>not</em> checked is whether a console is free. A play ticket exists to be sold
 * while every console is busy; that is the entire premise of the queue.
 */
@Service
public class PlayTicketSettlementService implements PlayTicketSettlement {

    private static final Logger log = LoggerFactory.getLogger(PlayTicketSettlementService.class);

    /** {@code queue_entries.source} for a token sold over the counter rather than booked ahead. */
    static final String SOURCE_PLAY_TICKET = "PLAY_TICKET";

    /** Who a ticket belongs to when nobody typed a name and no member is on the bill. */
    static final String WALK_IN = "Walk-in guest";

    private final QueueTokenService tokens;
    private final QueueEntryRepository entries;
    private final StationLookup stations;
    private final LiveEvents live;
    private final SyncOutboxWriter outbox;
    private final Clock clock;

    public PlayTicketSettlementService(QueueTokenService tokens, QueueEntryRepository entries,
                                       StationLookup stations, LiveEvents live,
                                       SyncOutboxWriter outbox, Clock clock) {
        this.tokens = tokens;
        this.entries = entries;
        this.stations = stations;
        this.live = live;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Prices each ticket off the console's rate card at the moment of sale — morning window
     * included, exactly as a session block is priced. The answer is a snapshot from here on: it
     * rides onto the queue entry, and the prepaid blocks a seat inserts are born at it however the
     * rate card has moved by then (invariant §5.11).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<QuotedTicket> quote(List<TicketSale> sales, String memberName) {
        if (sales == null || sales.isEmpty()) {
            return List.of();
        }
        OffsetDateTime at = now(clock);
        List<QuotedTicket> quoted = new ArrayList<>(sales.size());
        for (TicketSale sale : sales) {
            requireSellable(sale);
            quoted.add(new QuotedTicket(sale.consoleType().trim().toUpperCase(java.util.Locale.ROOT),
                    nameFor(sale.playerName(), memberName), sale.blocks(),
                    stations.blockPriceOf(sale.consoleType(), at)));
        }
        return List.copyOf(quoted);
    }

    /**
     * Issues one daily token per quoted ticket, in sale order, through the one door a token can
     * come from ({@link QueueTokenIssuing}). The counter is shared with booking check-ins, so a
     * ticket sold between two arrivals takes the number between theirs (invariant §5.10).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<IssuedTicket> register(long txId, List<QuotedTicket> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }
        List<IssuedTicket> issued = new ArrayList<>(quotes.size());
        for (QuotedTicket quote : quotes) {
            QueueTokenIssuing.IssuedToken token = tokens.issue(new QueueTokenIssuing.TokenRequest(
                    SOURCE_PLAY_TICKET, null, txId, quote.playerName(), quote.consoleType(),
                    quote.blocks(), quote.amount()));
            issued.add(new IssuedTicket(token.queueEntryId(), token.tokenNo(), token.tokenDate(),
                    quote.playerName(), quote.consoleType(), quote.blocks(), quote.amount()));
        }
        log.info("transaction {} sold {} play ticket(s): {}", txId, issued.size(),
                issued.stream().map(ticket -> "#%02d %s x%d".formatted(ticket.tokenNo(),
                        ticket.consoleType(), ticket.blocks())).toList());
        return List.copyOf(issued);
    }

    /**
     * A voided sale's tokens stop being seatable. The money has gone back out as a reversal, so a
     * token still WAITING against it would be an hour nobody paid for; a token already SEATED is
     * left as it is, because that time has been played and the session is the void's business.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int revoke(long txId) {
        List<QueueEntry> revoked = entries.findByTxIdOrderByIdAsc(txId).stream()
                .filter(QueueEntry::isWaiting)
                .toList();
        revoked.forEach(entry -> entry.setStatus(QueueEntryStatus.REFUNDED));
        if (!revoked.isEmpty()) {
            log.info("transaction {} voided — {} waiting token(s) revoked: {}", txId,
                    revoked.size(), revoked.stream().map(QueueEntry::getId).toList());
            revoked.forEach(entry -> outbox.record(SyncOutboxWriter.QUEUE_ENTRIES,
                    SyncOutboxWriter.REVOKED, entry.getId(),
                    SyncOutboxWriter.data("txId", txId)));
            live.queueChanged();
        }
        return revoked.size();
    }

    // ---- guards -----------------------------------------------------------------------------

    /**
     * 400, not 409: a ticket for zero half hours or for a console the rate card has never heard of
     * is a malformed request, not a floor situation the operator can resolve.
     */
    private static void requireSellable(TicketSale sale) {
        if (sale.consoleType() == null || sale.consoleType().isBlank()) {
            throw ValidationFailedException.onField("playTickets[].consoleType",
                    "A play ticket is sold for a console type");
        }
        if (sale.blocks() < 1) {
            throw ValidationFailedException.onField("playTickets[].blocks",
                    "A play ticket is at least one 30-minute block");
        }
    }

    /** Free text, else the member on the bill, else the walk-in the token is handed to. */
    private static String nameFor(String playerName, String memberName) {
        if (playerName != null && !playerName.isBlank()) {
            return playerName.trim();
        }
        return memberName != null && !memberName.isBlank() ? memberName : WALK_IN;
    }
}
