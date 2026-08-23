package dev.gamersden.booking.domain;

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
 * {@code bookings} — one prepaid slot (docs/bookings.md §2, §5).
 *
 * <p>{@code txId} is {@code NOT NULL} and has no setter: a booking cannot exist without the sale
 * that paid for it, which is what invariant §5.7 means by "reconciliation is structural". Pay
 * first is not a policy the service can forget — the schema refuses the row otherwise.
 *
 * <p>{@code playAmount}, {@code packageFee} and {@code cutoffHours} are snapshots taken at the
 * moment of sale. Nothing reads them back off {@code booking_settings} or the rate card
 * afterwards, so a {@code PUT /booking-settings} or a {@code PUT /pricing} applies to new
 * bookings only and the customer keeps the deal they were sold (invariant §5.11).
 *
 * <p>Nothing derived is stored (invariant §5.4): the total is the two snapshots added up, the end
 * of the slot is {@code startAt} plus its blocks, and the cancellation deadline is
 * {@code startAt} minus {@code cutoffHours} — all computed at read time, right here.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    /** One block of prepaid play (docs/bookings.md §2). */
    public static final int BLOCK_MINUTES = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "member_id")
    private Long memberId;

    /** Free-text or the attached member's name — whoever the slot is held for. */
    @Column(nullable = false)
    private String name;

    @Column
    private String phone;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    /** 30-minute units prepaid. */
    @Column(nullable = false)
    private int blocks;

    /** {@code blocks ×} the console's block rate at the moment of sale. */
    @Column(name = "play_amount", nullable = false)
    private int playAmount;

    @Column(name = "package_fee", nullable = false)
    private int packageFee;

    @Column(name = "cutoff_hours", nullable = false)
    private int cutoffHours;

    @Column(name = "tx_id", nullable = false)
    private Long txId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.PAID;

    @Column(name = "refund_tx_id")
    private Long refundTxId;

    /** The daily token issued at check-in; {@code null} until then. */
    @Column(name = "queue_entry_id")
    private Long queueEntryId;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Booking() {
    }

    public Booking(Long stationId, Long memberId, String name, String phone, OffsetDateTime startAt,
                   int blocks, int playAmount, int packageFee, int cutoffHours, Long txId) {
        this.stationId = stationId;
        this.memberId = memberId;
        this.name = name;
        this.phone = phone;
        this.startAt = startAt;
        this.blocks = blocks;
        this.playAmount = playAmount;
        this.packageFee = packageFee;
        this.cutoffHours = cutoffHours;
        this.txId = txId;
    }

    // ---- derived (never stored — invariant §5.4) ----------------------------------------------

    /** What the customer paid: prepaid play time plus the package fee snapshot. */
    public int total() {
        return playAmount + packageFee;
    }

    /** When the prepaid time runs out, if the slot were played back to back from {@code startAt}. */
    public OffsetDateTime endAt() {
        return startAt.plusMinutes((long) blocks * BLOCK_MINUTES);
    }

    /**
     * The last moment this booking may be cancelled: {@code start_at − cutoff_hours}, off the
     * booking's own snapshot rather than the current setting (invariant §5.11).
     */
    public OffsetDateTime cancellableUntil() {
        return startAt.minusHours(cutoffHours);
    }

    /** True while {@code now} is still on the free side of the cutoff — the boundary itself is in. */
    public boolean cancellableAt(OffsetDateTime now) {
        return status.isOpen() && !now.isAfter(cancellableUntil());
    }

    /** True when this booking's prepaid slot overlaps {@code other}'s on the same console. */
    public boolean overlaps(Booking other) {
        return stationId.equals(other.stationId)
                && startAt.isBefore(other.endAt())
                && other.startAt.isBefore(endAt());
    }

    // ---- accessors ----------------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public Long getStationId() {
        return stationId;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public OffsetDateTime getStartAt() {
        return startAt;
    }

    public int getBlocks() {
        return blocks;
    }

    public int getPlayAmount() {
        return playAmount;
    }

    public int getPackageFee() {
        return packageFee;
    }

    public int getCutoffHours() {
        return cutoffHours;
    }

    public Long getTxId() {
        return txId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public Long getRefundTxId() {
        return refundTxId;
    }

    public void setRefundTxId(Long refundTxId) {
        this.refundTxId = refundTxId;
    }

    public Long getQueueEntryId() {
        return queueEntryId;
    }

    public void setQueueEntryId(Long queueEntryId) {
        this.queueEntryId = queueEntryId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
