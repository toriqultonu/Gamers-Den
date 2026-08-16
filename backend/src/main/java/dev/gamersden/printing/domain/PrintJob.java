package dev.gamersden.printing.domain;

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
 * {@code print_jobs} — bytes are rendered once at job creation and stored, so a retry re-sends the
 * exact same paper and a reprint is a new job carrying its reason (invariant §5.5).
 */
@Entity
@Table(name = "print_jobs")
public class PrintJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrintJobType type;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrintJobStatus status = PrintJobStatus.QUEUED;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "is_reprint", nullable = false)
    private boolean reprint = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "reprint_reason")
    private ReprintReason reprintReason;

    @Column(name = "original_job_id")
    private Long originalJobId;

    /** Final ESC/POS byte stream. */
    @Column(nullable = false)
    private byte[] rendered;

    /** 48-column preview — matches the paper exactly. */
    @Column(name = "rendered_text", nullable = false)
    private String renderedText;

    @Column(name = "error")
    private String error;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected PrintJob() {
    }

    public PrintJob(PrintJobType type, Long refId, String deviceId, Long operatorId,
                    byte[] rendered, String renderedText) {
        this.type = type;
        this.refId = refId;
        this.deviceId = deviceId;
        this.operatorId = operatorId;
        this.rendered = rendered;
        this.renderedText = renderedText;
    }

    public Long getId() {
        return id;
    }

    public PrintJobType getType() {
        return type;
    }

    public Long getRefId() {
        return refId;
    }

    public PrintJobStatus getStatus() {
        return status;
    }

    public void setStatus(PrintJobStatus status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public boolean isReprint() {
        return reprint;
    }

    public ReprintReason getReprintReason() {
        return reprintReason;
    }

    public Long getOriginalJobId() {
        return originalJobId;
    }

    /** A reprint always carries its reason and links back to the paper it replaces. */
    public void markReprintOf(Long originalJobId, ReprintReason reason) {
        this.reprint = true;
        this.originalJobId = originalJobId;
        this.reprintReason = reason;
    }

    public byte[] getRendered() {
        return rendered;
    }

    public String getRenderedText() {
        return renderedText;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
