package dev.gamersden.settings.domain;

/**
 * A stored login background on its way out of {@code GET /terminal-settings/login-bg/{imageId}}.
 *
 * @param imageId     the id it was fetched by
 * @param contentType the media type sniffed at upload
 * @param bytes       the image itself
 */
public record LoginBackground(String imageId, String contentType, byte[] bytes) {
}
