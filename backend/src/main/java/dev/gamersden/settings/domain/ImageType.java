package dev.gamersden.settings.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The image formats a login background may be uploaded in, identified by their magic bytes.
 *
 * <p>The part's own {@code Content-Type} header is a claim, not evidence: a browser sends whatever
 * the OS guessed from the extension, and an unfriendly client sends whatever it likes. Since the
 * stored media type is replayed to every viewer of
 * {@code GET /terminal-settings/login-bg/{imageId}}, it is sniffed from the leading bytes instead,
 * which both validates "it is really an image" and keeps the serve response honest.
 */
public enum ImageType {

    PNG("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
    JPEG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
    /** RIFF container: {@code "RIFF" ....(size) "WEBP"} — the size word is skipped. */
    WEBP("image/webp", new byte[] {'R', 'I', 'F', 'F'});

    private final String contentType;
    private final byte[] magic;

    ImageType(String contentType, byte[] magic) {
        this.contentType = contentType;
        this.magic = magic;
    }

    public String contentType() {
        return contentType;
    }

    /** What the caller is allowed to send, for the 400's message. */
    public static String allowedContentTypes() {
        return String.join(", ", Arrays.stream(values()).map(ImageType::contentType).toList());
    }

    /** The format these bytes actually are, or empty when they are not an accepted image. */
    public static Optional<ImageType> sniff(byte[] bytes) {
        return Arrays.stream(values()).filter(type -> type.matches(bytes)).findFirst();
    }

    private boolean matches(byte[] bytes) {
        if (bytes == null || bytes.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }
        // "RIFF" alone is any RIFF file (a .wav opens with it too); WebP names itself at offset 8.
        return this != WEBP || (bytes.length >= 12
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P');
    }
}
