package dev.gamersden.printing.domain;

/**
 * One physical printer, as the queue sees it — the seam usb4java sits behind and the fake port
 * replaces in CI and dev (ARCHITECTURE.md §2, docs/backend-architecture.md §10).
 *
 * <p>The interface is deliberately tiny, and {@link #write(byte[])} deliberately takes the
 * <em>whole</em> ticket in one call. That is what makes "no interleaved tickets"
 * (docs/backend-architecture.md §5) a property of the port rather than of scheduling: two jobs
 * cannot overlap on the same device because a job is a single indivisible write, and
 * implementations serialise on themselves.
 *
 * <p>Nothing here renders. The bytes were rendered once, at job creation, and stored (invariant
 * §5.5); the port's only job is to get exactly those bytes onto paper — which is what makes a
 * retry byte-identical to the ticket it is replacing.
 */
public interface PrinterPort {

    /** Stable device id — what {@code GET /printers} lists and {@code PUT /printers/default} names. */
    String id();

    /** What a human calls it on S11. */
    String name();

    /**
     * A live DLE EOT poll (docs/backend-architecture.md §5). Never cached: the point of asking is
     * that the answer changes between one ticket and the next.
     */
    PrinterStatus status();

    /**
     * Sends one complete ticket. Returns only once the bytes are on paper.
     *
     * @throws PrinterTransportException carrying {@link PrintFailure#MID_PRINT} when part of the
     *                                   ticket made it out, or the matching cause otherwise
     */
    void write(byte[] bytes);
}
