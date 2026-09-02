package dev.gamersden.tournament.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.spi.SaleRefunding;
import dev.gamersden.common.spi.StationLookup;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.tournament.repo.TournamentEntryRepository;
import dev.gamersden.tournament.repo.TournamentRepository;
import dev.gamersden.tournament.repo.TournamentStationBlockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tournament configuration (docs/tournaments.md §1–§2): create, edit, block consoles, cancel.
 * Manager+ throughout — the guards live on {@code TournamentController}, and this class assumes
 * they passed.
 *
 * <p>Two rules shape everything here.
 *
 * <p><strong>Configuration stops being editable once money has changed hands.</strong> The entry
 * fee a player paid is not recorded on their entry row — the schema deliberately keeps one fee per
 * event (§2) — so letting the fee move after the first ticket is sold would leave the cancel
 * refund guessing what to hand back. The cap is the same story from the other end: dropping it
 * below the entries already sold would describe a bracket that cannot be built. Both are 409.
 *
 * <p><strong>Cancelling gives the money back and lets the consoles go.</strong> Status is the whole
 * release mechanism — a block only holds a station while its event is OPEN or LIVE (§2), so
 * {@code CANCELLED} frees every seat without touching {@code tournament_station_blocks}, and the
 * rows stay as the record of what was held. The refunds are negative transactions posted to the
 * shift doing the cancelling, one per originating sale (invariant §5.7), and they are written in
 * the same transaction as the status change: a cancel that rolls back cannot leave a player
 * un-refunded, and a refund that fails cannot leave a tournament half-cancelled.
 */
@Service
public class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);

    private final TournamentRepository tournaments;
    private final TournamentEntryRepository entries;
    private final TournamentStationBlockRepository blocks;
    private final StationLookup stations;
    private final SaleRefunding refunds;
    private final LiveEvents live;
    private final SyncOutboxWriter outbox;

    public TournamentService(TournamentRepository tournaments,
                             TournamentEntryRepository entries,
                             TournamentStationBlockRepository blocks,
                             StationLookup stations,
                             SaleRefunding refunds,
                             LiveEvents live,
                             SyncOutboxWriter outbox) {
        this.tournaments = tournaments;
        this.entries = entries;
        this.blocks = blocks;
        this.stations = stations;
        this.refunds = refunds;
        this.live = live;
        this.outbox = outbox;
    }

    // ---- reads --------------------------------------------------------------------------------

    /** {@code GET /tournaments} — what is still selling or being played, soonest first. */
    @Transactional(readOnly = true)
    public List<Tournament> upcoming() {
        return tournaments.findByStatusInOrderByScheduledAtAsc(
                List.of(TournamentStatus.OPEN, TournamentStatus.LIVE));
    }

    /** {@code GET /tournaments/history} — finished and called-off events, most recent first. */
    @Transactional(readOnly = true)
    public List<Tournament> history() {
        return tournaments.findByStatusInOrderByScheduledAtDesc(
                List.of(TournamentStatus.DONE, TournamentStatus.CANCELLED));
    }

    @Transactional(readOnly = true)
    public Tournament get(long id) {
        return tournaments.findById(id).orElseThrow(() -> new NotFoundException("Tournament", id));
    }

    @Transactional(readOnly = true)
    public List<Long> stationIdsOf(long tournamentId) {
        return blocks.findByIdTournamentIdOrderByIdStationIdAsc(tournamentId).stream()
                .map(TournamentStationBlock::getStationId)
                .toList();
    }

    // ---- create / edit ------------------------------------------------------------------------

    @Transactional
    public Tournament create(String name, String game, Cadence cadence, OffsetDateTime scheduledAt,
                             int entryFee, int prizePool, int maxPlayers, int matchDurationMin) {
        String trimmed = name.trim();
        if (tournaments.existsByName(trimmed)) {
            throw duplicateName(trimmed);
        }
        Tournament created = tournaments.save(new Tournament(trimmed, game.trim(), cadence,
                scheduledAt, entryFee, prizePool, requireCap(maxPlayers), matchDurationMin,
                CurrentStaff.require().id()));
        log.info("tournament {} created: \"{}\" {} on {}, cap {}, fee {} BDT, prize {} BDT",
                created.getId(), created.getName(), created.getGame(), created.getScheduledAt(),
                created.getMaxPlayers(), created.getEntryFee(), created.getPrizePool());
        outbox.record(SyncOutboxWriter.TOURNAMENTS, SyncOutboxWriter.CREATED, created.getId(),
                describe(created));
        live.tournamentChanged(created.getId());
        return created;
    }

    /**
     * {@code PATCH /tournaments/{id}} — every field optional. Only an event that is still selling
     * can be reconfigured; once the bracket is live its shape is the bracket's, not the form's.
     */
    @Transactional
    public Tournament update(long id, String name, String game, Cadence cadence,
                             OffsetDateTime scheduledAt, Integer entryFee, Integer prizePool,
                             Integer maxPlayers, Integer matchDurationMin) {
        Tournament tournament = lock(id);
        requireOpen(tournament, "edited");
        int sold = entries.countByTournamentId(id);

        if (name != null) {
            String trimmed = name.trim();
            if (!trimmed.equals(tournament.getName()) && tournaments.existsByName(trimmed)) {
                throw duplicateName(trimmed);
            }
            tournament.setName(trimmed);
        }
        if (game != null) {
            tournament.setGame(game.trim());
        }
        if (cadence != null) {
            tournament.setCadence(cadence);
        }
        if (scheduledAt != null) {
            tournament.setScheduledAt(scheduledAt);
        }
        if (entryFee != null && entryFee != tournament.getEntryFee()) {
            requireNothingSold(tournament, sold, "the entry fee");
            tournament.setEntryFee(entryFee);
        }
        if (prizePool != null) {
            tournament.setPrizePool(prizePool);
        }
        if (maxPlayers != null && maxPlayers != tournament.getMaxPlayers()) {
            requireCap(maxPlayers);
            if (maxPlayers < sold) {
                throw new ConflictException(ErrorCode.CONFLICT,
                        "%s has %d entries — the cap cannot drop to %d"
                                .formatted(tournament.getName(), sold, maxPlayers),
                        Map.of("tournamentId", id, "entries", sold, "maxPlayers", maxPlayers));
            }
            tournament.setMaxPlayers(maxPlayers);
        }
        if (matchDurationMin != null) {
            tournament.setMatchDurationMin(matchDurationMin);
        }
        log.info("tournament {} updated", id);
        outbox.record(SyncOutboxWriter.TOURNAMENTS, SyncOutboxWriter.UPDATED, id,
                describe(tournament));
        live.tournamentChanged(id);
        return tournament;
    }

    /**
     * {@code PUT /tournaments/{id}/blocks} — the console allocation, replaced wholesale.
     *
     * <p>Overlapping allocations are allowed on purpose: two events scheduled weeks apart are both
     * OPEN, and both legitimately name the same consoles. The schema's promise is that each event
     * only ever draws from its own rows (§2), which the bracket engine keeps — it is not a promise
     * that a console belongs to one event at a time.
     *
     * <p>A live walk-in session is likewise no obstacle. Blocking says "hold this seat for the
     * event"; whether it happens to be occupied right now is a question for match start (§4), and
     * the sessions already on it keep running.
     */
    @Transactional
    public List<Long> setStationBlocks(long id, List<Long> stationIds) {
        Tournament tournament = lock(id);
        requireNotFinished(tournament, "blocked");
        Set<Long> wanted = new LinkedHashSet<>(stationIds == null ? List.of() : stationIds);
        wanted.forEach(stationId -> stations.find(stationId)
                .orElseThrow(() -> new NotFoundException("Station", stationId)));

        blocks.deleteByIdTournamentId(id);
        blocks.flush();
        wanted.forEach(stationId -> blocks.save(new TournamentStationBlock(id, stationId)));
        log.info("tournament {} now blocks {} console(s): {}", id, wanted.size(), wanted);
        outbox.record(SyncOutboxWriter.TOURNAMENTS, SyncOutboxWriter.CONSOLES_BLOCKED, id,
                SyncOutboxWriter.data("stationIds", List.copyOf(wanted)));
        live.tournamentChanged(id);
        // A blocked console reads RESERVED on the Floor and an unblocked one goes back to free,
        // so both sides of the change are cards that have to move.
        wanted.forEach(live::stationChanged);
        return List.copyOf(wanted);
    }

    // ---- cancel -------------------------------------------------------------------------------

    /**
     * {@code POST /tournaments/{id}/cancel} — called off, everyone paid back.
     *
     * <p>Entries are refunded per originating sale rather than per ticket: three friends who
     * bought their tickets on one receipt get one negative transaction back, which is what the
     * drawer and the X/Z report will show. Each entry is flagged {@code refunded} so a second
     * cancel — or a later partial refund — can never pay for it twice.
     */
    @Transactional
    public Cancellation cancel(long id, String reason) {
        Tournament tournament = lock(id);
        if (tournament.getStatus().isFinished()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s is already %s".formatted(tournament.getName(), tournament.getStatus()),
                    Map.of("tournamentId", id, "status", tournament.getStatus().name()));
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        tournament.setStatus(TournamentStatus.CANCELLED);
        tournament.setCancelledReason(why);

        Map<Long, List<TournamentEntry>> bySale = new LinkedHashMap<>();
        entries.findRefundableOf(id)
                .forEach(entry -> bySale.computeIfAbsent(entry.getTxId(), tx -> new ArrayList<>())
                        .add(entry));

        List<SaleRefunding.Refund> issued = new ArrayList<>(bySale.size());
        int refunded = 0;
        for (Map.Entry<Long, List<TournamentEntry>> sale : bySale.entrySet()) {
            int amount = sale.getValue().size() * tournament.getEntryFee();
            sale.getValue().forEach(entry -> entry.setRefunded(true));
            refunded += sale.getValue().size();
            if (amount > 0) {
                issued.add(refunds.refund(new SaleRefunding.RefundRequest(sale.getKey(), amount,
                        SaleRefunding.Bucket.TOURNAMENT,
                        "%s cancelled".formatted(tournament.getName()))));
            }
        }
        log.info("tournament {} (\"{}\") cancelled by staff {}: \"{}\" — {} entries refunded across "
                        + "{} sale(s)", id, tournament.getName(), CurrentStaff.require().id(), why,
                refunded, issued.size());
        outbox.record(SyncOutboxWriter.TOURNAMENTS, SyncOutboxWriter.CANCELLED, id,
                SyncOutboxWriter.data("reason", why,
                        "entriesRefunded", refunded,
                        "refundTxIds", issued.stream()
                                .map(SaleRefunding.Refund::transactionId).toList()));
        live.tournamentChanged(id);
        // CANCELLED releases every console the event was holding (§2) — those cards are free now.
        stationIdsOf(id).forEach(live::stationChanged);
        return new Cancellation(tournament, refunded, List.copyOf(issued));
    }

    /** The configuration the cloud mirrors: what the event is, what it costs, how big it is. */
    private static java.util.Map<String, Object> describe(Tournament tournament) {
        return SyncOutboxWriter.data("name", tournament.getName(),
                "game", tournament.getGame(),
                "cadence", tournament.getCadence().name(),
                "scheduledAt", tournament.getScheduledAt().toString(),
                "entryFee", tournament.getEntryFee(),
                "prizePool", tournament.getPrizePool(),
                "maxPlayers", tournament.getMaxPlayers(),
                "matchDurationMin", tournament.getMatchDurationMin(),
                "status", tournament.getStatus().name());
    }

    /**
     * What a cancel did.
     *
     * @param entriesRefunded every entry that still owed money, now flagged {@code refunded}
     * @param refunds         one negative transaction per originating sale; a free event has none
     */
    public record Cancellation(Tournament tournament,
                               int entriesRefunded,
                               List<SaleRefunding.Refund> refunds) {
    }

    // ---- guards -------------------------------------------------------------------------------

    private Tournament lock(long id) {
        return tournaments.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Tournament", id));
    }

    private static int requireCap(int cap) {
        if (!Tournament.CAPS.contains(cap)) {
            throw ValidationFailedException.onField("maxPlayers",
                    "A perfect bracket needs a power-of-two cap — one of " + Tournament.CAPS);
        }
        return cap;
    }

    private static void requireOpen(Tournament tournament, String what) {
        if (tournament.getStatus() != TournamentStatus.OPEN) {
            throw new ConflictException(ErrorCode.TOURNAMENT_NOT_OPEN,
                    "%s is %s and can no longer be %s"
                            .formatted(tournament.getName(), tournament.getStatus(), what),
                    Map.of("tournamentId", tournament.getId(),
                            "status", tournament.getStatus().name()));
        }
    }

    private static void requireNotFinished(Tournament tournament, String what) {
        if (tournament.getStatus().isFinished()) {
            throw new ConflictException(ErrorCode.TOURNAMENT_NOT_OPEN,
                    "%s is %s and can no longer be %s"
                            .formatted(tournament.getName(), tournament.getStatus(), what),
                    Map.of("tournamentId", tournament.getId(),
                            "status", tournament.getStatus().name()));
        }
    }

    /**
     * The fee is one column shared by every ticket sold, so moving it after a sale would rewrite
     * history — including what a cancel has to refund. 409 rather than a silent snapshot: the
     * operator who wants a different fee wants a different event.
     */
    private static void requireNothingSold(Tournament tournament, int sold, String what) {
        if (sold > 0) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "%s has already sold %d entries — %s cannot change"
                            .formatted(tournament.getName(), sold, what),
                    Map.of("tournamentId", tournament.getId(), "entries", sold));
        }
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException(ErrorCode.DUPLICATE_NAME,
                "Tournament name \"%s\" is already taken".formatted(name), Map.of("name", name));
    }
}
