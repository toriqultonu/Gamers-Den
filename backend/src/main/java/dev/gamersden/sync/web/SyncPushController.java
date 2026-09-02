package dev.gamersden.sync.web;

import dev.gamersden.common.config.GamersDenProperties;
import dev.gamersden.common.error.UnauthorizedException;
import dev.gamersden.sync.domain.SyncPushRequest;
import dev.gamersden.sync.domain.SyncPushResponse;
import dev.gamersden.sync.domain.SyncPusher;
import dev.gamersden.sync.domain.SyncOutboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The cloud's receiver: {@code POST /sync/push}, ordered, idempotent by op id (api-contract.md,
 * "Live updates &amp; sync").
 *
 * <p>Registered only where {@code gamersden.sync.receive-enabled} is on — the {@code cloud}
 * profile. The venue box runs the same JAR and must not expose a door into its own tables
 * (ARCHITECTURE.md §6).
 *
 * <p>Authenticated by the shared {@code SYNC_TOKEN}, not by a JWT: the caller is a machine with no
 * staff, no shift and no terminal, so the bearer chain has nothing to say about it. The comparison
 * is constant-time and a blank configured token refuses everything rather than letting anyone in —
 * a mirror nobody gave a secret to is closed, not open.
 */
@RestController
@RequestMapping("/sync")
@Tag(name = "Sync")
@ConditionalOnProperty(name = "gamersden.sync.receive-enabled", havingValue = "true")
public class SyncPushController {

    private final SyncOutboxService outbox;
    private final byte[] expectedToken;

    public SyncPushController(SyncOutboxService outbox, GamersDenProperties properties) {
        this.outbox = outbox;
        String token = properties.sync().token();
        this.expectedToken = (token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/push")
    @Operation(summary = "Receive a batch of venue ops",
            description = "One-way venue → cloud. Ops already held are skipped by opId, so a "
                    + "batch the venue re-sent after losing the response lands as duplicates "
                    + "rather than as a second sale.")
    public SyncPushResponse push(@RequestHeader(name = SyncPusher.TOKEN_HEADER, required = false)
                                 String token,
                                 @Valid @RequestBody SyncPushRequest request) {
        requireToken(token);
        int accepted = outbox.receive(request.ops());
        return new SyncPushResponse(accepted, request.ops().size() - accepted);
    }

    private void requireToken(String presented) {
        byte[] offered = (presented == null ? "" : presented).getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, offered)) {
            throw new UnauthorizedException("A valid " + SyncPusher.TOKEN_HEADER + " is required");
        }
    }
}
