package dev.gamersden.sync.domain;

/**
 * What the sync chip says (design.md §4: "synced / syncing / offline since HH:MM").
 *
 * <p>Three states because the chip has three, and the mapping is the honest one: OFFLINE means the
 * last attempt to reach the cloud failed, SYNCING means the venue is ahead of it, SYNCED means the
 * outbox is empty. Nothing here is a health check of the venue itself — it stays fully operational
 * with the cloud down for a day (docs/backend-architecture.md §11).
 */
public enum SyncState {

    /** Nothing pending; the cloud has everything the venue has written. */
    SYNCED,

    /** Ops are waiting, and the last push (if there was one) went through. */
    SYNCING,

    /** The last attempt failed. Ops keep piling up and drain on reconnect. */
    OFFLINE
}
