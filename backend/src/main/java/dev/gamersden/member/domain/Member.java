package dev.gamersden.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * {@code members} — wallet and points are integer BDT / integer points, guarded non-negative by
 * DB CHECKs; every movement also lands in the matching ledger.
 */
@Entity
@Table(name = "members")
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(name = "preferred_console")
    private String preferredConsole;

    /** {@code TEXT[]} — free-text favourites shown on the member card. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "games", columnDefinition = "text[]")
    private String[] games;

    @Column(nullable = false)
    private int wallet = 0;

    @Column(nullable = false)
    private int points = 0;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Member() {
    }

    public Member(String name, String phone) {
        this.name = name;
        this.phone = phone;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPreferredConsole() {
        return preferredConsole;
    }

    public void setPreferredConsole(String preferredConsole) {
        this.preferredConsole = preferredConsole;
    }

    public String[] getGames() {
        return games;
    }

    public void setGames(String[] games) {
        this.games = games;
    }

    public int getWallet() {
        return wallet;
    }

    public void setWallet(int wallet) {
        this.wallet = wallet;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
