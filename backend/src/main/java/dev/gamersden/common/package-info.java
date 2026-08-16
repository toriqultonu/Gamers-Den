/**
 * Shared kernel: error envelope + {@link dev.gamersden.common.error.ApiException} hierarchy,
 * trace-id/access logging, venue-clock and money helpers, pagination envelope, OpenAPI wiring.
 * The idempotency filter ({@code idempotency_keys}) and the SSE hub land here in B04 and B19.
 *
 * <p>Only truly cross-cutting code belongs here — feature logic lives in its own package.
 */
package dev.gamersden.common;
