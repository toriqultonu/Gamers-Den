package dev.gamersden.settings.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /terminal-settings/login-bg} and the endpoint that serves what it stored — the
 * "pick file / remove" control design.md §6 puts under "Login screen".
 *
 * <p>Two things are being pinned here. One is that an upload is validated by its own bytes rather
 * than by the part's {@code Content-Type}, because that stored type is replayed to every viewer of
 * the serve URL. The other is that the picture is readable <em>without</em> a token: S1 draws it
 * before anyone has signed in, so a guarded URL could never render where the feature is meant to
 * appear.
 */
class LoginBackgroundIT extends AbstractApiIntegrationTest {

    private static final String SETTINGS = "/api/v1/terminal-settings";
    private static final String UPLOAD = SETTINGS + "/login-bg";

    @Test
    @DisplayName("an admin uploads a background; anyone — signed in or not — can fetch it back")
    void uploadThenRetrieve() {
        byte[] uploaded = png();

        ResponseEntity<JsonNode> upload = upload(rest, uploaded, "venue.png", "image/png", adminBearer());

        assertThat(upload.getStatusCode().value()).isEqualTo(200);
        String imageId = upload.getBody().get("loginBgImageId").asText();
        assertThat(imageId).isNotBlank();

        // No Authorization header at all: this is the login screen, before the PIN pad.
        ResponseEntity<byte[]> served = rest.exchange(UPLOAD + "/" + imageId, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), byte[].class);

        assertThat(served.getStatusCode().value()).isEqualTo(200);
        assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(served.getBody()).isEqualTo(uploaded);
    }

    @Test
    @DisplayName("the id lands on the settings object, and the settings read reports it")
    void theUploadedIdIsTheSettingsField() {
        String imageId = uploadPng();

        assertThat(get(SETTINGS, adminBearer()).getBody().get("loginBgImageId").asText())
                .isEqualTo(imageId);
        assertThat(jdbc.queryForObject(
                "SELECT login_bg_content_type FROM terminal_settings WHERE terminal = ?",
                String.class, TERMINAL)).isEqualTo("image/png");
    }

    @Test
    @DisplayName("the stored media type is sniffed from the bytes, not taken from the part header")
    void theStoredTypeComesFromTheBytes() {
        // A JPEG that the client insists is a PNG — a browser guessing from a renamed file.
        String imageId = upload(rest, jpeg(), "venue.png", "image/png", adminBearer())
                .getBody().get("loginBgImageId").asText();

        ResponseEntity<byte[]> served = rest.exchange(UPLOAD + "/" + imageId, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), byte[].class);

        assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    @DisplayName("re-uploading mints a new id and the old URL stops resolving")
    void replacingTheBackgroundRetiresTheOldId() {
        String first = uploadPng();
        String second = upload(rest, jpeg(), "new.jpg", "image/jpeg", adminBearer())
                .getBody().get("loginBgImageId").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(anonymousGet(first).getStatusCode().value()).isEqualTo(404);
        assertThat(anonymousGet(second).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("PUT with loginBgImageId null removes the picture, bytes and all")
    void removingTheBackground() {
        String imageId = uploadPng();

        JsonNode after = put(SETTINGS, TerminalSettingsAccessIT.settingsBody(Map.of()), adminBearer())
                .getBody();

        assertThat(after.get("loginBgImageId").isNull()).isTrue();
        assertThat(anonymousGet(imageId).getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM terminal_settings WHERE terminal = ? AND login_bg IS NOT NULL",
                Integer.class, TERMINAL)).isZero();
    }

    @Test
    @DisplayName("a PUT keeping the current id keeps the picture")
    void keepingTheBackground() {
        String imageId = uploadPng();

        JsonNode after = put(SETTINGS,
                TerminalSettingsAccessIT.settingsBody(Map.of("loginBgImageId", imageId)),
                adminBearer()).getBody();

        assertThat(after.get("loginBgImageId").asText()).isEqualTo(imageId);
        assertThat(anonymousGet(imageId).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a made-up loginBgImageId is refused rather than stored")
    void anUnknownImageIdIsRejected() {
        uploadPng();

        ResponseEntity<JsonNode> response = put(SETTINGS,
                TerminalSettingsAccessIT.settingsBody(Map.of("loginBgImageId", "not-an-id")),
                adminBearer());

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("error").get("details").get("field").asText())
                .isEqualTo("loginBgImageId");
    }

    @Test
    @DisplayName("one terminal cannot adopt another terminal's background")
    void anotherTerminalsImageIdIsRejected() {
        String onT1 = uploadPng();

        HttpHeaders onT2 = bearer(login(adminId, ADMIN_PIN, "T2").getBody().get("accessToken").asText());
        ResponseEntity<JsonNode> response = put(SETTINGS,
                TerminalSettingsAccessIT.settingsBody(Map.of("loginBgImageId", onT1)), onT2);

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a file that is not an image is refused, whatever it calls itself")
    void nonImageUploadsAreRefused() {
        byte[] notAnImage = "PK not a picture".getBytes(StandardCharsets.UTF_8);

        assertErrorEnvelope(upload(rest, notAnImage, "notes.txt", "text/plain", adminBearer()),
                400, "VALIDATION_FAILED");
        // The same bytes wearing an accepted content type get no further: the sniff decides.
        assertErrorEnvelope(upload(rest, notAnImage, "venue.png", "image/png", adminBearer()),
                400, "VALIDATION_FAILED");
        // An image format the venue does not accept is refused on its own magic bytes.
        assertErrorEnvelope(upload(rest, gif(), "venue.gif", "image/gif", adminBearer()),
                400, "VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM terminal_settings WHERE login_bg IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("an oversized upload is refused with the documented envelope, not a container error")
    void oversizedUploadsAreRefused() {
        // Past gamersden.settings.login-bg.max-size (2 MB) but well inside the multipart backstop,
        // so the answer is this application's 400 rather than the container's abort. The size
        // check runs before the bytes are sniffed, which is why a PNG header on padding is enough.
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, tooBig, 0, 8);

        ResponseEntity<JsonNode> response = upload(rest, tooBig, "huge.png", "image/png", adminBearer());

        assertErrorEnvelope(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("error").get("message").asText()).contains("2048 KB");
    }

    @Test
    @DisplayName("an empty part is refused")
    void anEmptyUploadIsRefused() {
        assertErrorEnvelope(upload(rest, new byte[0], "empty.png", "image/png", adminBearer()),
                400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("an unknown image id is a 404 in the error envelope")
    void unknownImageIdIsNotFound() {
        ResponseEntity<JsonNode> response = rest.exchange(UPLOAD + "/does-not-exist", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), JsonNode.class);

        assertErrorEnvelope(response, 404, "NOT_FOUND");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private String uploadPng() {
        return upload(rest, png(), "venue.png", "image/png", adminBearer())
                .getBody().get("loginBgImageId").asText();
    }

    private ResponseEntity<byte[]> anonymousGet(String imageId) {
        return rest.exchange(UPLOAD + "/" + imageId, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), byte[].class);
    }

    /** A multipart POST of one {@code file} part, the shape the S13 ImagePicker sends. */
    static ResponseEntity<JsonNode> upload(TestRestTemplate rest, byte[] bytes, String filename,
                                           String partContentType, HttpHeaders auth) {
        ByteArrayResource part = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(partContentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(part, partHeaders));

        HttpHeaders headers = new HttpHeaders();
        if (auth != null) {
            headers.addAll(auth);
        }
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange(UPLOAD, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    static byte[] png() {
        return encode("png");
    }

    static byte[] jpeg() {
        return encode("jpg");
    }

    /** GIF is a real image and still not accepted — the venue takes PNG, JPEG and WebP. */
    private static byte[] gif() {
        return encode("gif");
    }

    private static byte[] encode(String format) {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xEC3013);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
