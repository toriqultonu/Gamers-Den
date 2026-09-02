package dev.gamersden.sync.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The body of {@code POST /sync/push} — one batch, oldest op first.
 *
 * <p>Lives beside {@link SyncOp} rather than in {@code web/} because both ends of the wire use it:
 * the venue's {@link SyncPusher} sends this shape and the cloud's controller receives it, and a
 * machine-to-machine payload with two implementations in one codebase is worth spelling once.
 */
@Schema(name = "SyncPush", description = "A batch of venue ops, ordered, idempotent by opId")
public record SyncPushRequest(@NotNull @Size(max = 1000) @Valid List<SyncOp> ops) {
}
