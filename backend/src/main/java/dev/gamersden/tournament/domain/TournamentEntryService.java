package dev.gamersden.tournament.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.common.spi.TournamentEntrySettlement;
import dev.gamersden.tournament.repo.TournamentEntryRepository;
import dev.gamersden.tournament.repo.TournamentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entries: the {@code tournament} package's answer to {@link TournamentEntrySettlement}, plus the
 * reads and the door check behind {@code POST /tournament-entries/{id}/check-in}
 * (docs/tournaments.md §5, §7).
 *
 * <p>Deliberately separate from {@link TournamentService}. This bean is what {@code billing} calls
 * <em>into</em> during a settle; {@code TournamentService} is what calls <em>out</em> to
 * {@code billing} to sell and to refund. Keeping the two directions in different beans is what
 * stops the two packages from forming a construction cycle.
 *
 * <p>Nothing here writes an entry outside a money transaction: {@link Propagation#MANDATORY} on
 * both settlement methods makes that structural, and {@code tournament_entries.tx_id} being
 * {@code NOT NULL} makes it unforgeable. A registered player is a player who has paid
 * (invariant §5.7).
 */
@Service
public class TournamentEntryService implements TournamentEntrySettlement {

    private static final Logger log = LoggerFactory.getLogger(TournamentEntryService.class);

    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final BracketService brackets;
    private final LiveEvents live;
    private final SyncOutboxWriter outbox;

    public TournamentEntryService(TournamentRepository tournaments,
                                  TournamentEntryRepository entries,
                                  BracketService brackets,
                                  LiveEvents live,
                                  SyncOutboxWriter outbox) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.brackets = brackets;
        this.live = live;
        this.outbox = outbox;
    }

    // ---- the settle path ----------------------------------------------------------------------

    /**
     * Prices and seeds a batch of entries. Each distinct tournament is locked once and stays
     * locked to commit, so the capacity this reads is the capacity the insert will meet — two
     * terminals racing for the last slot queue here rather than colliding at
     * {@code UNIQUE (tournament_id, seed)} with the money already written.
     *
     * <p>Seeds run on within the batch too: three tickets bought in one sale take the next three
     * numbers, in the order the operator listed them (docs/tournaments.md §5).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<QuotedEntry> quote(List<EntrySale> sales, String memberName) {
        if (sales == null || sales.isEmpty()) {
            return List.of();
        }
        Map<Long, Tournament> locked = new HashMap<>();
        Map<Long, Integer> nextSeed = new HashMap<>();
        List<QuotedEntry> quoted = new ArrayList<>(sales.size());

        for (EntrySale sale : sales) {
            Tournament tournament = locked.computeIfAbsent(sale.tournamentId(), this::lock);
            requireOpen(tournament);
            int seed = nextSeed.computeIfAbsent(tournament.getId(),
                    id -> entries.countByTournamentId(id) + 1);
            requireRoom(tournament, seed);
            nextSeed.put(tournament.getId(), seed + 1);
            quoted.add(new QuotedEntry(tournament.getId(), tournament.getName(),
                    TournamentEntry.nameFor(sale.playerName(), memberName),
                    tournament.getEntryFee(), seed));
        }
        return List.copyOf(quoted);
    }

    /**
     * Writes the entries, then draws the bracket if this sale was the one that filled the field.
     *
     * <p>The draw belongs here rather than to a listener or a scheduled sweep because
     * docs/tournaments.md §3 asks for it in <em>the same transaction</em> as the sale: the ticket
     * that completed the field and the bracket it completed commit together or not at all. The
     * tournament row is already locked from {@link #quote}, so no second sale can slip a player in
     * between the count and the draw, and no second draw can start from another terminal.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RegisteredEntry> register(long txId, Long memberId, List<QuotedEntry> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }
        List<RegisteredEntry> registered = new ArrayList<>(quotes.size());
        Set<Long> touched = new LinkedHashSet<>();
        for (QuotedEntry quote : quotes) {
            TournamentEntry entry = entries.save(new TournamentEntry(quote.tournamentId(), memberId,
                    quote.playerName(), txId, quote.seed()));
            log.info("tournament {} entry {} registered as seed #{} for \"{}\" on transaction {} "
                            + "({} BDT)", quote.tournamentId(), entry.getId(), entry.getSeed(),
                    entry.getPlayerName(), txId, quote.fee());
            outbox.record(SyncOutboxWriter.TOURNAMENT_ENTRIES, SyncOutboxWriter.REGISTERED,
                    entry.getId(), SyncOutboxWriter.data(
                            "tournamentId", quote.tournamentId(),
                            "memberId", memberId,
                            "playerName", entry.getPlayerName(),
                            "seed", entry.getSeed(),
                            "fee", quote.fee(),
                            "txId", txId));
            registered.add(new RegisteredEntry(entry.getId(), quote.tournamentId(),
                    quote.tournamentName(), entry.getPlayerName(), entry.getSeed(),
                    entry.getQrToken()));
            touched.add(quote.tournamentId());
        }
        touched.forEach(brackets::generateIfFull);
        // Slots left is what the POS card disables on, so every event this sale touched is sent —
        // once, whether it took one ticket or three (docs/tournaments.md §5).
        touched.forEach(live::tournamentChanged);
        return List.copyOf(registered);
    }

    // ---- check-in -----------------------------------------------------------------------------

    /**
     * {@code POST /tournament-entries/{id}/check-in} — the QR on the P5 stub, scanned at the door
     * (docs/tournaments.md §7). Any role: this is execution, not configuration.
     *
     * <p>The token has to match the id it is presented against. The id alone is a small integer
     * anyone could type; the token is the ticket, and checking both is what makes a scan mean
     * "this piece of paper was here" rather than "somebody knows entry 12 exists".
     */
    @Transactional
    public TournamentEntry checkIn(long entryId, String qrToken) {
        TournamentEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new NotFoundException("Tournament entry", entryId));
        if (qrToken == null || !qrToken.trim().equals(entry.getQrToken())) {
            throw ValidationFailedException.onField("qrToken",
                    "That QR does not belong to entry %d".formatted(entryId));
        }
        if (entry.isRefunded()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "Entry %d was refunded — the ticket is no longer valid".formatted(entryId),
                    Map.of("entryId", entryId));
        }
        if (entry.isCheckedIn()) {
            throw new ConflictException(ErrorCode.ALREADY_CHECKED_IN,
                    "%s has already checked in".formatted(entry.getPlayerName()),
                    Map.of("entryId", entryId, "seed", entry.getSeed()));
        }
        entry.setCheckedIn(true);
        log.info("tournament {} entry {} (seed #{}) checked in", entry.getTournamentId(), entryId,
                entry.getSeed());
        outbox.record(SyncOutboxWriter.TOURNAMENT_ENTRIES, SyncOutboxWriter.CHECKED_IN, entryId,
                SyncOutboxWriter.data("tournamentId", entry.getTournamentId(),
                        "seed", entry.getSeed()));
        live.tournamentChanged(entry.getTournamentId());
        return entry;
    }

    // ---- reads --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TournamentEntry> of(long tournamentId) {
        return entries.findByTournamentIdOrderBySeedAsc(tournamentId);
    }

    @Transactional(readOnly = true)
    public int countOf(long tournamentId) {
        return entries.countByTournamentId(tournamentId);
    }

    /**
     * The name on one ticket — what a card shows for its champion (§8) without pulling the whole
     * field back for a list of finished events.
     */
    @Transactional(readOnly = true)
    public String playerNameOf(Long entryId) {
        return entryId == null ? null
                : entries.findById(entryId).map(TournamentEntry::getPlayerName).orElse(null);
    }

    // ---- guards -------------------------------------------------------------------------------

    private Tournament lock(Long tournamentId) {
        return tournaments.findByIdForUpdate(tournamentId)
                .orElseThrow(() -> new NotFoundException("Tournament", tournamentId));
    }

    private static void requireOpen(Tournament tournament) {
        if (tournament.getStatus() != TournamentStatus.OPEN) {
            throw new ConflictException(ErrorCode.TOURNAMENT_NOT_OPEN,
                    "%s is %s — entries are only sold while it is OPEN"
                            .formatted(tournament.getName(), tournament.getStatus()),
                    Map.of("tournamentId", tournament.getId(),
                            "status", tournament.getStatus().name()));
        }
    }

    private static void requireRoom(Tournament tournament, int seed) {
        if (seed > tournament.getMaxPlayers()) {
            throw new ConflictException(ErrorCode.TOURNAMENT_FULL,
                    "%s is full — all %d slots are sold"
                            .formatted(tournament.getName(), tournament.getMaxPlayers()),
                    Map.of("tournamentId", tournament.getId(),
                            "maxPlayers", tournament.getMaxPlayers()));
        }
    }
}
