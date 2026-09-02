package dev.gamersden.settings.web;

import dev.gamersden.common.error.ValidationFailedException;
import dev.gamersden.common.security.CurrentStaff;
import dev.gamersden.common.security.Roles;
import dev.gamersden.settings.domain.LoginBackground;
import dev.gamersden.settings.domain.TerminalSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * {@code /terminal-settings} (api-contract.md, Settings; design.md §6) — the S13 preferences that
 * belong to a terminal rather than to a person.
 *
 * <p>Which terminal is never asked for and never sent: it is the access token's {@code terminal}
 * claim, so an operator configures the machine they are signed in at and cannot reach another's
 * row by naming it.
 *
 * <p>Every role reads — the theme, the text size and the accent decide how the app paints itself
 * for whoever is on shift — and only an Admin writes, which is the "terminal settings write" row
 * of §1's permission matrix. The API is where that rule is kept; S13 hiding its controls from a
 * cashier is cosmetic.
 */
@RestController
@RequestMapping("/terminal-settings")
@Tag(name = "Settings")
public class TerminalSettingsController {

    /**
     * The path the login screen fetches a background from, before anyone has signed in. Spelled
     * once here and once in {@code SecurityConfig}'s public routes; they must agree.
     */
    public static final String LOGIN_BG_PATH = "/terminal-settings/login-bg";

    /** Ids are minted per upload and never reused, so a served picture can be cached hard. */
    private static final CacheControl IMMUTABLE = CacheControl
            .maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final TerminalSettingsService settings;

    public TerminalSettingsController(TerminalSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "This terminal's settings",
            description = "A terminal that has never been configured answers with the defaults: "
                    + "dark theme, default text size, Den Red, no background, sound on, 5-minute "
                    + "auto-lock, 1 receipt copy.")
    public TerminalSettingsView get() {
        return TerminalSettingsView.of(settings.get(terminal()));
    }

    @PutMapping
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Replace this terminal's settings (Admin)",
            description = "The whole object; every field is required except loginBgImageId, which "
                    + "carries the id of the terminal's uploaded background or null to remove it.")
    public TerminalSettingsView update(@Valid @RequestBody UpdateTerminalSettingsRequest request) {
        return TerminalSettingsView.of(settings.update(terminal(), request.toUpdate()));
    }

    @PostMapping(path = "/login-bg", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Upload this terminal's login background (Admin)",
            description = "PNG, JPEG or WebP, validated by its own bytes rather than by the part's "
                    + "Content-Type. Replaces whatever the terminal had; the new id is what "
                    + "GET /terminal-settings/login-bg/{imageId} is fetched by.")
    public LoginBgUploadedView uploadLoginBg(@RequestParam("file") MultipartFile file) {
        return new LoginBgUploadedView(settings.uploadLoginBg(terminal(), bytesOf(file),
                file.getContentType()));
    }

    /**
     * The picture itself. Deliberately outside the filter chain's authenticated routes: S1 draws
     * it under the brand statement <em>before</em> anyone has a token (design.md §1, S1), so a
     * guarded URL could never be rendered where the feature is meant to appear. What guards it is
     * the id — a random per-upload value, holding nothing but a photograph the venue chose for its
     * own login screen.
     */
    @GetMapping("/login-bg/{imageId}")
    @Operation(summary = "Serve a login background",
            description = "Public: the login screen renders it before sign-in. 404 once the "
                    + "background is removed or replaced.")
    public ResponseEntity<byte[]> loginBg(@PathVariable String imageId) {
        LoginBackground image = settings.loginBackground(imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .cacheControl(IMMUTABLE)
                .eTag("\"" + image.imageId() + "\"")
                .body(image.bytes());
    }

    private static String terminal() {
        return CurrentStaff.require().terminal();
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ValidationFailedException.onField("file", "The upload could not be read");
        }
    }
}
