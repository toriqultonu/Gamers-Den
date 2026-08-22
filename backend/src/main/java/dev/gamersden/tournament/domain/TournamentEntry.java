package dev.gamersden.tournament.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * {@code tournament_entries} — one sold ticket (docs/tournaments.md §2).
 *
 * <p>{@code txId} is {@code NOT NULL} in the schema and there is no setter here: an entry cannot
 * exist without the sale that paid for it, which is what invariant §5.7 means by "reconciliation
 * is structural". It is also why nothing in this package can register a player outside the
 * transaction that took the money.
 *
 * <p>{@code seed} is the sale order and is printed as {@code TOKEN #NN}. It is a per-tournament
 * counter — deliberately <em>not</em> the daily {@code token_seq} the queue and bookings share
 * (invariant §5.10).
 */
@Entity
@Table(name = "tournament_entries")
public class TournamentEntry {

    /** 128 bits, exactly as the schema comment asks — the opaque payload of the P5 QR. */
    private static final int QR_TOKEN_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a ticket says when nobody gave a name (docs/tournaments.md §5). */
    public static final String WALK_IN = "Walk-in guest";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "player_name", nullable = false)
    private String playerName;

    @Column(name = "tx_id", nullable = false)
    private Long txId;

    @Column(nullable = false)
    private int seed;

    @Column(name = "qr_token", nullable = false, unique = true)
    private String qrToken;

    @Column(name = "checked_in", nullable = false)
    private boolean checkedIn = false;

    @Column(nullable = false)
    private boolean refunded = false;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected TournamentEntry() {
    }

    public TournamentEntry(Long tournamentId, Long memberId, String playerName, Long txId, int seed) {
        this.tournamentId = tournamentId;
        this.memberId = memberId;
        this.playerName = playerName;
        this.txId = txId;
        this.seed = seed;
        this.qrToken = newQrToken();
    }

    /**
     * A fresh opaque token. Random rather than derived from the ids: the QR is scanned at the door
     * by whoever is holding the paper, so it must not be guessable from a seed number.
     */
    public static String newQrToken() {
        byte[] bytes = new byte[QR_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The name on the stub: what was typed, else the member's, else "Walk-in guest" (§5). */
    public static String nameFor(String typed, String memberName) {
        if (typed != null && !typed.isBlank()) {
            return typed.trim();
        }
        if (memberName != null && !memberName.isBlank()) {
            return memberName.trim();
        }
        return WALK_IN;
    }

    public Long getId() {
        return id;
    }

    public Long getTournamentId() {
        return tournamentId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public Long getTxId() {
        return txId;
    }

    public int getSeed() {
        return seed;
    }

    public String getQrToken() {
        return qrToken;
    }

    public boolean isCheckedIn() {
        return checkedIn;
    }

    public void setCheckedIn(boolean checkedIn) {
        this.checkedIn = checkedIn;
    }

    public boolean isRefunded() {
        return refunded;
    }

    public void setRefunded(boolean refunded) {
        this.refunded = refunded;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
