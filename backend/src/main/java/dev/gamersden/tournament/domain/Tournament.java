package dev.gamersden.tournament.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * {@code tournaments} — one configured event (docs/tournaments.md §2).
 *
 * <p>{@code maxPlayers} is restricted to 2ⁿ by a database CHECK as well as by
 * {@link #requireValidCap(int)}, because the whole bracket engine (B13) is built on "exactly N−1
 * matches, no byes" — a cap of 6 would not be a smaller tournament, it would be a different
 * algorithm.
 *
 * <p>{@code winnerEntryId} is written by B13 when the final is decided. It is a plain column here
 * rather than an association: {@code tournament_entries} points back at {@code tournaments}, and
 * mapping both directions as managed relations would make the insert order of a cap-filling sale
 * depend on Hibernate's flush order instead of on this package's own code.
 */
@Entity
@Table(name = "tournaments")
public class Tournament {

    /** The caps a perfect bracket can be built from (docs/tournaments.md §3). */
    public static final java.util.Set<Integer> CAPS = java.util.Set.of(4, 8, 16, 32);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String game;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Cadence cadence;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "entry_fee", nullable = false)
    private int entryFee;

    @Column(name = "prize_pool", nullable = false)
    private int prizePool;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers = 8;

    @Column(name = "match_duration_min", nullable = false)
    private int matchDurationMin = 20;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TournamentStatus status = TournamentStatus.OPEN;

    @Column(name = "winner_entry_id")
    private Long winnerEntryId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "cancelled_reason")
    private String cancelledReason;

    protected Tournament() {
    }

    public Tournament(String name, String game, Cadence cadence, OffsetDateTime scheduledAt,
                      int entryFee, int prizePool, int maxPlayers, int matchDurationMin,
                      Long createdBy) {
        this.name = name;
        this.game = game;
        this.cadence = cadence;
        this.scheduledAt = scheduledAt;
        this.entryFee = entryFee;
        this.prizePool = prizePool;
        this.maxPlayers = maxPlayers;
        this.matchDurationMin = matchDurationMin;
        this.createdBy = createdBy;
    }

    /** Mirrors the {@code max_players IN (4,8,16,32)} CHECK, so the refusal is a 400, not a 500. */
    public static int requireValidCap(int cap) {
        if (!CAPS.contains(cap)) {
            throw new IllegalArgumentException("maxPlayers must be one of " + CAPS);
        }
        return cap;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGame() {
        return game;
    }

    public void setGame(String game) {
        this.game = game;
    }

    public Cadence getCadence() {
        return cadence;
    }

    public void setCadence(Cadence cadence) {
        this.cadence = cadence;
    }

    public OffsetDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(OffsetDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public int getEntryFee() {
        return entryFee;
    }

    public void setEntryFee(int entryFee) {
        this.entryFee = entryFee;
    }

    public int getPrizePool() {
        return prizePool;
    }

    public void setPrizePool(int prizePool) {
        this.prizePool = prizePool;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = requireValidCap(maxPlayers);
    }

    public int getMatchDurationMin() {
        return matchDurationMin;
    }

    public void setMatchDurationMin(int matchDurationMin) {
        this.matchDurationMin = matchDurationMin;
    }

    public TournamentStatus getStatus() {
        return status;
    }

    public void setStatus(TournamentStatus status) {
        this.status = status;
    }

    public Long getWinnerEntryId() {
        return winnerEntryId;
    }

    public void setWinnerEntryId(Long winnerEntryId) {
        this.winnerEntryId = winnerEntryId;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getCancelledReason() {
        return cancelledReason;
    }

    public void setCancelledReason(String cancelledReason) {
        this.cancelledReason = cancelledReason;
    }
}
