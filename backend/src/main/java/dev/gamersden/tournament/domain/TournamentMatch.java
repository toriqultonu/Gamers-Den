package dev.gamersden.tournament.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * {@code tournament_matches} — one node of the bracket tree (docs/tournaments.md §2).
 *
 * <p>The tree is held by {@code nextMatchId} alone: a winner advances along that link and nothing
 * else describes the shape. {@code (round, slot)} is the human coordinate the UI draws columns
 * from — round 1 is the first round, the last round is the single final, and the final is the one
 * match whose {@code nextMatchId} is {@code null} (docs/tournaments.md §3).
 *
 * <p>Every reference is a plain id column rather than a JPA association, for the same reason
 * {@link Tournament#getWinnerEntryId()} is: the generator writes a whole bracket in one pass, from
 * the final backwards, so that each match's successor already has an id by the time the link is
 * set. Managed relations would hand that ordering to Hibernate's flush instead.
 *
 * <p>{@code stationId}, {@code startedAt} and {@code extraMin} are the execution columns (§4,
 * B14). They ship on the entity because they ship on the table; B13 only ever reads
 * {@code startedAt}, and then only to decide who is allowed to record a winner.
 */
@Entity
@Table(name = "tournament_matches")
public class TournamentMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "round", nullable = false)
    private int round;

    @Column(name = "slot", nullable = false)
    private int slot;

    @Column(name = "entry_a")
    private Long entryA;

    @Column(name = "entry_b")
    private Long entryB;

    @Column(name = "winner_entry")
    private Long winnerEntry;

    @Column(name = "next_match_id")
    private Long nextMatchId;

    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "extra_min", nullable = false)
    private int extraMin = 0;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    protected TournamentMatch() {
    }

    public TournamentMatch(Long tournamentId, int round, int slot, Long entryA, Long entryB,
                           Long nextMatchId) {
        this.tournamentId = tournamentId;
        this.round = round;
        this.slot = slot;
        this.entryA = entryA;
        this.entryB = entryB;
        this.nextMatchId = nextMatchId;
    }

    /**
     * A first-round match one player walked into: a real entry on one side, an empty bracket
     * position on the other. It is decided the moment the bracket is drawn (§3, "byes
     * auto-advance") — never played, never assigned a console.
     *
     * <p>Only the first round can hold one. A half-filled match higher up is not a bye but a match
     * still waiting for the round below to produce its other player, and the two must never be
     * confused: the first would be walked through at the draw, the second would advance somebody
     * into a final nobody had reached yet.
     */
    public boolean isBye() {
        return round == 1 && (entryA == null) != (entryB == null);
    }

    /** Both sides known, so it can actually be played. */
    public boolean isReady() {
        return entryA != null && entryB != null;
    }

    public boolean isDecided() {
        return winnerEntry != null;
    }

    /** True while this match is being played — what decides the role needed to record a winner. */
    public boolean hasStarted() {
        return startedAt != null;
    }

    /** The final: the one match nothing follows, so its winner is the champion. */
    public boolean isFinal() {
        return nextMatchId == null;
    }

    /**
     * Which side of the next match this winner lands on. Slots are numbered from 1, and slots
     * {@code 2k-1} and {@code 2k} feed slot {@code k} of the round above — the odd one takes the
     * top half of that pairing, the even one the bottom.
     */
    public boolean feedsSideA() {
        return slot % 2 == 1;
    }

    public boolean has(Long entryId) {
        return entryId != null && (entryId.equals(entryA) || entryId.equals(entryB));
    }

    public Long getId() {
        return id;
    }

    public Long getTournamentId() {
        return tournamentId;
    }

    public int getRound() {
        return round;
    }

    public int getSlot() {
        return slot;
    }

    public Long getEntryA() {
        return entryA;
    }

    public void setEntryA(Long entryA) {
        this.entryA = entryA;
    }

    public Long getEntryB() {
        return entryB;
    }

    public void setEntryB(Long entryB) {
        this.entryB = entryB;
    }

    public Long getWinnerEntry() {
        return winnerEntry;
    }

    public void setWinnerEntry(Long winnerEntry) {
        this.winnerEntry = winnerEntry;
    }

    public Long getNextMatchId() {
        return nextMatchId;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public int getExtraMin() {
        return extraMin;
    }

    public void setExtraMin(int extraMin) {
        this.extraMin = extraMin;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(Long decidedBy) {
        this.decidedBy = decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(OffsetDateTime decidedAt) {
        this.decidedAt = decidedAt;
    }
}
