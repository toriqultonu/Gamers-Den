package dev.gamersden.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.ErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * {@code Idempotency-Key} enforcement for the money and print routes (api-contract.md §1,
 * ARCHITECTURE.md §5.2):
 *
 * <ul>
 *   <li>no key on a guarded route → 400 {@code VALIDATION_FAILED};</li>
 *   <li>first call → runs, and its response is stored for 48 h;</li>
 *   <li>identical retry → the stored response plus {@code Idempotency-Replayed: true};</li>
 *   <li>same key, different body → 409 {@code IDEMPOTENCY_REPLAY};</li>
 *   <li>same key while the first call is still running → 409 {@code CONFLICT}.</li>
 * </ul>
 *
 * <p>Only 2xx responses are stored. A rejected request (a 409 {@code SPLIT_MISMATCH}, say) has
 * changed nothing, so the caller is free to fix the payload and retry under the same key.
 *
 * <p>The filter sits behind the security chain: an unauthenticated caller gets its 401 before the
 * key is ever looked at.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /** Guarded payloads are a handful of lines of JSON; anything larger is not a settle. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyPolicy policy;
    private final IdempotencyStore store;
    private final ErrorResponseWriter errors;
    private final ObjectMapper mapper;

    public IdempotencyFilter(IdempotencyPolicy policy,
                             IdempotencyStore store,
                             ErrorResponseWriter errors,
                             ObjectMapper mapper) {
        this.policy = policy;
        this.store = store;
        this.errors = errors;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !policy.guards(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawKey = request.getHeader(KEY_HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            errors.write(response, ErrorCode.VALIDATION_FAILED,
                    KEY_HEADER + " header is required on this endpoint",
                    Map.of("header", KEY_HEADER));
            return;
        }
        UUID key;
        try {
            key = UUID.fromString(rawKey.trim());
        } catch (IllegalArgumentException ex) {
            errors.write(response, ErrorCode.VALIDATION_FAILED,
                    KEY_HEADER + " must be a UUID",
                    Map.of("header", KEY_HEADER));
            return;
        }

        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        if (body.length > MAX_BODY_BYTES) {
            errors.write(response, ErrorCode.VALIDATION_FAILED,
                    "Request body is too large for an idempotent endpoint",
                    Map.of("maxBytes", MAX_BODY_BYTES));
            return;
        }

        IdempotencyStore.Outcome outcome = store.begin(key, fingerprint(request, body));
        switch (outcome.kind()) {
            case REPLAY -> replay(response, outcome);
            case MISMATCH -> errors.write(response, ErrorCode.IDEMPOTENCY_REPLAY,
                    KEY_HEADER + " was already used for a different request",
                    Map.of("header", KEY_HEADER));
            case IN_FLIGHT -> errors.write(response, ErrorCode.CONFLICT,
                    "A request with this " + KEY_HEADER + " is still in progress",
                    Map.of("header", KEY_HEADER));
            case RESERVED -> execute(key, request, response, chain, body);
        }
    }

    private void execute(UUID key,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         FilterChain chain,
                         byte[] body) throws ServletException, IOException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyHttpServletRequest(request, body), captured);
            int status = captured.getStatus();
            if (status >= HttpStatus.OK.value() && status < HttpStatus.MULTIPLE_CHOICES.value()) {
                String json = asStorableJson(captured);
                if (json != null) {
                    store.complete(key, status, json);
                    stored = true;
                }
            }
        } finally {
            if (!stored) {
                store.release(key);
            }
            captured.copyBodyToResponse();
        }
    }

    private void replay(HttpServletResponse response, IdempotencyStore.Outcome outcome) throws IOException {
        response.setStatus(outcome.statusCode());
        response.setHeader(REPLAYED_HEADER, "true");
        String body = outcome.responseBody();
        if (body == null || body.isBlank() || IdempotencyStore.NO_BODY.equals(body)) {
            response.flushBuffer();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }

    /**
     * The stored body must survive a {@code jsonb} round trip. An empty body becomes the JSON null
     * literal; anything non-JSON is refused rather than stored, and the key is released.
     */
    private String asStorableJson(ContentCachingResponseWrapper captured) {
        byte[] payload = captured.getContentAsByteArray();
        if (payload.length == 0) {
            return IdempotencyStore.NO_BODY;
        }
        String json = new String(payload, StandardCharsets.UTF_8);
        try {
            mapper.readTree(json);
            return json;
        } catch (JsonProcessingException ex) {
            log.warn("Response on a guarded route was not JSON — the idempotency key was released "
                    + "and a retry will re-run the request");
            return null;
        }
    }

    /** method + path + body: the same key on a different route is as much a mismatch as a new body. */
    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every JVM", ex);
        }
    }
}
