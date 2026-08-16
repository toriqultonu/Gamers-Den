package dev.gamersden.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/**
 * {@code transactions} — the immutable money snapshot (invariant §5.4). Refunds are separate
 * negative transactions, never edits. Every row carries a {@code shiftId} so X/Z reconciliation is
 * structural, and the {@code tournamentAmount} / {@code bookingAmount} splits feed their own
 * report lines with the same query as all takings (invariant §5.7).
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human/barcode id printed on the receipt, e.g. {@code GD-2608-047}. */
    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "cart_id")
    private Long cartId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Column(name = "gaming_amount", nullable = false)
    private int gamingAmount = 0;

    @Column(name = "fnb_amount", nullable = false)
    private int fnbAmount = 0;

    @Column(name = "tournament_amount", nullable = false)
    private int tournamentAmount = 0;

    @Column(name = "booking_amount", nullable = false)
    private int bookingAmount = 0;

    @Column(name = "points_redeemed", nullable = false)
    private int pointsRedeemed = 0;

    @Column(name = "points_earned", nullable = false)
    private int pointsEarned = 0;

    /** Negative for refunds. */
    @Column(name = "total_due", nullable = false)
    private int totalDue;

    @Column(nullable = false)
    private boolean voided = false;

    @Column(name = "void_reason")
    private String voidReason;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Transaction() {
    }

    public Transaction(String publicId, Long shiftId, Long staffId, int totalDue) {
        this.publicId = publicId;
        this.shiftId = shiftId;
        this.staffId = staffId;
        this.totalDue = totalDue;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getCartId() {
        return cartId;
    }

    public void setCartId(Long cartId) {
        this.cartId = cartId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public void setMemberId(Long memberId) {
        this.memberId = memberId;
    }

    public Long getShiftId() {
        return shiftId;
    }

    public Long getStaffId() {
        return staffId;
    }

    public int getGamingAmount() {
        return gamingAmount;
    }

    public void setGamingAmount(int gamingAmount) {
        this.gamingAmount = gamingAmount;
    }

    public int getFnbAmount() {
        return fnbAmount;
    }

    public void setFnbAmount(int fnbAmount) {
        this.fnbAmount = fnbAmount;
    }

    public int getTournamentAmount() {
        return tournamentAmount;
    }

    public void setTournamentAmount(int tournamentAmount) {
        this.tournamentAmount = tournamentAmount;
    }

    public int getBookingAmount() {
        return bookingAmount;
    }

    public void setBookingAmount(int bookingAmount) {
        this.bookingAmount = bookingAmount;
    }

    public int getPointsRedeemed() {
        return pointsRedeemed;
    }

    public void setPointsRedeemed(int pointsRedeemed) {
        this.pointsRedeemed = pointsRedeemed;
    }

    public int getPointsEarned() {
        return pointsEarned;
    }

    public void setPointsEarned(int pointsEarned) {
        this.pointsEarned = pointsEarned;
    }

    public int getTotalDue() {
        return totalDue;
    }

    public boolean isVoided() {
        return voided;
    }

    public void setVoided(boolean voided) {
        this.voided = voided;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
