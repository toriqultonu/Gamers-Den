package dev.gamersden.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.OffsetDateTime;

/** {@code terminal_settings} — one row per terminal, keyed by terminal name (S13). */
@Entity
@Table(name = "terminal_settings")
public class TerminalSettings {

    @Id
    @Column(name = "terminal", nullable = false)
    private String terminal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Theme theme = Theme.DARK;

    @Enumerated(EnumType.STRING)
    @Column(name = "font_scale", nullable = false)
    private FontScale fontScale = FontScale.DEFAULT;

    @Column(nullable = false)
    private String accent = Accent.DEFAULT.hex();

    /** Admin-uploaded login background (B21). Null together with the two columns below. */
    @Column(name = "login_bg")
    private byte[] loginBg;

    /** The id {@code GET /terminal-settings/login-bg/{imageId}} is addressed by (V005). */
    @Column(name = "login_bg_image_id")
    private String loginBgImageId;

    /** Sniffed from the bytes at upload, replayed on the serve response (V005). */
    @Column(name = "login_bg_content_type")
    private String loginBgContentType;

    @Column(nullable = false)
    private boolean sound = true;

    /** 0 = off. */
    @Column(name = "auto_lock_min", nullable = false)
    private int autoLockMin = 5;

    @Column(name = "receipt_copies", nullable = false)
    private int receiptCopies = 1;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    protected TerminalSettings() {
    }

    public TerminalSettings(String terminal) {
        this.terminal = terminal;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public String getTerminal() {
        return terminal;
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
    }

    public FontScale getFontScale() {
        return fontScale;
    }

    public void setFontScale(FontScale fontScale) {
        this.fontScale = fontScale;
    }

    public String getAccent() {
        return accent;
    }

    public void setAccent(String accent) {
        this.accent = accent;
    }

    public byte[] getLoginBg() {
        return loginBg;
    }

    public String getLoginBgImageId() {
        return loginBgImageId;
    }

    public String getLoginBgContentType() {
        return loginBgContentType;
    }

    /**
     * Replaces the background with a freshly identified one. The three columns only ever move
     * together — the V005 CHECK enforces that — so there is no setter for any of them alone.
     */
    public void setLoginBg(String imageId, String contentType, byte[] bytes) {
        this.loginBgImageId = imageId;
        this.loginBgContentType = contentType;
        this.loginBg = bytes;
    }

    /** design.md §6 "remove": the bytes go with the id, never left behind unreachable. */
    public void clearLoginBg() {
        setLoginBg(null, null, null);
    }

    public boolean isSound() {
        return sound;
    }

    public void setSound(boolean sound) {
        this.sound = sound;
    }

    public int getAutoLockMin() {
        return autoLockMin;
    }

    public void setAutoLockMin(int autoLockMin) {
        this.autoLockMin = autoLockMin;
    }

    public int getReceiptCopies() {
        return receiptCopies;
    }

    public void setReceiptCopies(int receiptCopies) {
        this.receiptCopies = receiptCopies;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
