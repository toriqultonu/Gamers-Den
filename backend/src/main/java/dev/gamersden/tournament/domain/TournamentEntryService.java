package dev.gamersden.tournament.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
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
import java.util.List;
import java.util.Map;

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

    public TournamentEntryService(TournamentRepository tournaments,
                                  TournamentEntryRepository entries) {
        this.tournaments = tournaments;
        this.entries = entries;
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RegisteredEntry> register(long txId, Long memberId, List<QuotedEntry> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return List.of();
        }
        List<RegisteredEntry> registered = new ArrayList<>(quotes.size());
        for (QuotedEntry quote : quotes) {
            TournamentEntry entry = entries.save(new TournamentEntry(quote.tournamentId(), memberId,
                    quote.playerName(), txId, quote.seed()));
            log.info("tournament {} entry {} registered as seed #{} for \"{}\" on transaction {} "
                            + "({} BDT)", quote.tournamentId(), entry.getId(), entry.getSeed(),
                    entry.getPlayerName(), txId, quote.fee());
            registered.add(new RegisteredEntry(entry.getId(), quote.tournamentId(),
                    quote.tournamentName(), entry.getPlayerName(), entry.getSeed(),
                    entry.getQrToken()));
        }
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
