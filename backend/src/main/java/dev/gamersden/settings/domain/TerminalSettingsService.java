package dev.gamersden.settings.domain;

import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.spi.ReceiptCopyPreference;
import dev.gamersden.settings.config.SettingsProperties;
import dev.gamersden.settings.repo.TerminalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code settings} package's door onto {@code terminal_settings} (ARCHITECTURE.md §3) — the
 * S13 preferences and the login background behind them.
 *
 * <p>One row per terminal, keyed by the access token's {@code terminal} claim: the settings belong
 * to the machine in the corner of the venue, not to whoever is signed in at it, which is why the
 * caller never names the terminal in a request body and cannot write another one's row.
 *
 * <p>A terminal that has never been configured has no row at all. {@link #get(String)} answers
 * with the column defaults instead of inserting one, so a read stays a read; the first
 * {@code PUT} is what creates it.
 */
@Service
public class TerminalSettingsService implements ReceiptCopyPreference {

    /** What the column defaults to, and what a terminal with no row of its own prints. */
    private static final int DEFAULT_COPIES = 1;

    /** design.md §6: "Off / 2 / 5 / 10 min", with Off stored as 0. */
    private static final List<Integer> AUTO_LOCK_CHOICES = List.of(0, 2, 5, 10);

    /** design.md §6: "Receipt copies — 1 / 2", and the CHECK on the column says the same. */
    private static final List<Integer> RECEIPT_COPY_CHOICES = List.of(1, 2);

    private final TerminalSettingsRepository settings;
    private final SettingsProperties properties;

    public TerminalSettingsService(TerminalSettingsRepository settings, SettingsProperties properties) {
        this.settings = settings;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public int receiptCopies(String terminal) {
        if (terminal == null || terminal.isBlank()) {
            return DEFAULT_COPIES;
        }
        return settings.findById(terminal)
                .map(TerminalSettings::getReceiptCopies)
                .orElse(DEFAULT_COPIES);
    }

    /**
     * This terminal's settings — the stored row, or an unsaved instance carrying the defaults when
     * the terminal has never been written. Readable by every role (api-contract.md §1: the write
     * is Admin-only, the read is not), because the theme, the text size and the accent decide how
     * the app paints itself for whoever is signed in.
     */
    @Transactional(readOnly = true)
    public TerminalSettings get(String terminal) {
        String key = requireTerminal(terminal);
        return settings.findById(key).orElseGet(() -> new TerminalSettings(key));
    }

    /** Replaces this terminal's settings, creating the row on first write. */
    @Transactional
    public TerminalSettings update(String terminal, TerminalSettingsUpdate update) {
        String key = requireTerminal(terminal);
        TerminalSettings row = settings.findById(key).orElseGet(() -> new TerminalSettings(key));

        row.setTheme(require(update.theme(), "theme"));
        row.setFontScale(require(update.fontScale(), "fontScale"));
        row.setAccent(accentHex(update.accent()));
        row.setSound(update.sound());
        row.setAutoLockMin(oneOf(update.autoLockMin(), AUTO_LOCK_CHOICES, "autoLockMin"));
        row.setReceiptCopies(oneOf(update.receiptCopies(), RECEIPT_COPY_CHOICES, "receiptCopies"));
        applyLoginBg(row, update.loginBgImageId());

        return settings.save(row);
    }

    /**
     * Stores an uploaded login background against this terminal and returns its new id.
     *
     * <p>Each upload mints a fresh id, so the serve URL of a replaced picture is never reused and
     * a cached one cannot go stale. There is at most one background per terminal — the picture
     * lives in the terminal's own row — so an upload replaces whatever was there.
     *
     * @param declaredContentType the part's own {@code Content-Type}; a claim, checked but never
     *                            stored — the type that is stored is sniffed from the bytes
     */
    @Transactional
    public String uploadLoginBg(String terminal, byte[] bytes, String declaredContentType) {
        String key = requireTerminal(terminal);
        ImageType type = validateImage(bytes, declaredContentType);

        TerminalSettings row = settings.findById(key).orElseGet(() -> new TerminalSettings(key));
        row.setLoginBg(UUID.randomUUID().toString(), type.contentType(), bytes);
        return settings.save(row).getLoginBgImageId();
    }

    /**
     * The picture behind {@code GET /terminal-settings/login-bg/{imageId}}. Ids are unique across
     * terminals, so an id is all the lookup needs.
     */
    @Transactional(readOnly = true)
    public LoginBackground loginBackground(String imageId) {
        return settings.findByLoginBgImageId(imageId)
                .map(row -> new LoginBackground(row.getLoginBgImageId(), row.getLoginBgContentType(),
                        row.getLoginBg()))
                .orElseThrow(() -> new NotFoundException("No login background with id " + imageId));
    }

    // ---- validation -----------------------------------------------------------------------

    /**
     * Keeps or removes the background. The only id a terminal may be set to is the one it already
     * holds: ids are minted by the upload, so anything else is either another terminal's picture
     * or a value the caller made up.
     */
    private static void applyLoginBg(TerminalSettings row, String requestedImageId) {
        if (requestedImageId == null) {
            row.clearLoginBg();
            return;
        }
        if (!requestedImageId.equals(row.getLoginBgImageId())) {
            throw ValidationFailedException.onField("loginBgImageId",
                    "loginBgImageId must be the id this terminal's last upload returned, or null "
                            + "to remove the background");
        }
    }

    private ImageType validateImage(byte[] bytes, String declaredContentType) {
        if (bytes == null || bytes.length == 0) {
            throw ValidationFailedException.onField("file", "An image file is required");
        }
        long maxBytes = properties.loginBg().maxSize().toBytes();
        if (bytes.length > maxBytes) {
            throw ValidationFailedException.onField("file",
                    "The login background must be at most %d KB".formatted(maxBytes / 1024));
        }
        if (declaredContentType != null && !declaredContentType.isBlank()
                && !declaredContentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw badImage("%s is not an image".formatted(declaredContentType));
        }
        return ImageType.sniff(bytes)
                .orElseThrow(() -> badImage("The uploaded file is not a readable image"));
    }

    private static ValidationFailedException badImage(String reason) {
        return ValidationFailedException.onField("file",
                "%s — send one of: %s".formatted(reason, ImageType.allowedContentTypes()));
    }

    private static String accentHex(String accent) {
        return Accent.ofHex(accent)
                .orElseThrow(() -> ValidationFailedException.onField("accent",
                        "accent must be one of: " + Accent.allowedHexes()))
                .hex();
    }

    private static int oneOf(int value, List<Integer> allowed, String field) {
        if (!allowed.contains(value)) {
            throw ValidationFailedException.onField(field,
                    "%s must be one of: %s".formatted(field,
                            String.join(", ", allowed.stream().map(String::valueOf).toList())));
        }
        return value;
    }

    private static <T> T require(T value, String field) {
        return Optional.ofNullable(value)
                .orElseThrow(() -> ValidationFailedException.onField(field, field + " is required"));
    }

    /**
     * Every access token carries a {@code terminal} claim (api-contract.md §1), so a blank one
     * means a token was issued outside {@code POST /auth/login} — not a caller mistake to explain,
     * but not something to key a settings row on either.
     */
    private static String requireTerminal(String terminal) {
        if (terminal == null || terminal.isBlank()) {
            throw ValidationFailedException.onField("terminal",
                    "This session's token carries no terminal; sign in again from the terminal");
        }
        return terminal;
    }
}
