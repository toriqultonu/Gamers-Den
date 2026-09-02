package dev.gamersden.settings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * {@code gamersden.settings.*} — the one knob B21 needs: how large a login background may be.
 *
 * <p>Kept below {@code spring.servlet.multipart.max-file-size} on purpose. The container's limit
 * is a backstop that aborts the upload mid-stream with a 413; this one is the rule the operator
 * actually meets, checked after the part is read so the answer is the documented 400
 * {@code VALIDATION_FAILED} envelope naming the field and the limit.
 *
 * @param loginBg the login-background upload limits (design.md §6 "Login screen")
 */
@ConfigurationProperties(prefix = "gamersden.settings")
public record SettingsProperties(LoginBg loginBg) {

    /**
     * @param maxSize largest accepted upload; a venue background is a photo, not a poster print
     */
    public record LoginBg(DataSize maxSize) {
    }
}
