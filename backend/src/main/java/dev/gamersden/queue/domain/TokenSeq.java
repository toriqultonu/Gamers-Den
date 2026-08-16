package dev.gamersden.queue.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * {@code token_seq} — the daily queue-token counter shared by bookings and play tickets
 * (invariant §5.10). Keyed by date, so it resets at Asia/Dhaka midnight; allocation is a
 * row-locked upsert, which is what keeps concurrent sales from colliding.
 */
@Entity
@Table(name = "token_seq")
public class TokenSeq {

    @Id
    @Column(name = "token_date", nullable = false)
    private LocalDate tokenDate;

    @Column(name = "next_no", nullable = false)
    private int nextNo = 1;

    protected TokenSeq() {
    }

    public TokenSeq(LocalDate tokenDate) {
        this.tokenDate = tokenDate;
    }

    public LocalDate getTokenDate() {
        return tokenDate;
    }

    public int getNextNo() {
        return nextNo;
    }

    public void setNextNo(int nextNo) {
        this.nextNo = nextNo;
    }
}
