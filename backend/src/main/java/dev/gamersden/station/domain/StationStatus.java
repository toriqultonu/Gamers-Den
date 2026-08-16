package dev.gamersden.station.domain;

/**
 * {@code stations.status} — the persisted lifecycle only. Floor states such as busy or reserved
 * are derived from live sessions and tournament blocks, never stored (invariant §5.4).
 */
public enum StationStatus {
    AVAILABLE,
    MAINTENANCE
}
