package dev.gamersden.settings.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code POST /terminal-settings/login-bg} — "multipart image upload → id" (api-contract.md,
 * Settings). The id is already stored against the terminal by the time this is returned; it comes
 * back so S13 can point the preview at the serve URL without re-reading the settings.
 */
@Schema(name = "LoginBgUploaded")
public record LoginBgUploadedView(String loginBgImageId) {
}
