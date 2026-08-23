package dev.gamersden.printing.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The printer CI and dev print to (ARCHITECTURE.md §2: "fake {@code PrinterPort} in CI").
 *
 * <p>It is a real participant in the queue, not a no-op: it keeps every ticket it was handed, so a
 * test can assert that a retry re-sent <em>identical bytes</em> rather than a fresh render, and it
 * can be told to be offline, out of paper, or to die part-way through the next write — the three
 * failure paths docs/backend-architecture.md §11 puts in the cross-cutting matrix and the only
 * ones no hardware-free build could otherwise exercise.
 *
 * <p>{@code synchronized} on the write for the reason {@link PrinterPort} gives: one ticket is one
 * write, and two of them must never interleave on the same device.
 */
public class FakePrinterPort implements PrinterPort {

    private static final Logger log = LoggerFactory.getLogger(FakePrinterPort.class);

    /** How much of a half-printed ticket the fake pretends made it onto paper. */
    private static final int MID_PRINT_FRACTION = 3;

    private final String id;
    private final String name;

    private final List<byte[]> printed = new ArrayList<>();
    private final List<byte[]> partials = new ArrayList<>();

    private PrinterStatus status = PrinterStatus.ONLINE;
    private PrintFailure writeFailure;
    private int writeFailuresLeft;

    public FakePrinterPort(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public synchronized PrinterStatus status() {
        return status;
    }

    @Override
    public synchronized void write(byte[] bytes) {
        if (status != PrinterStatus.ONLINE) {
            throw new PrinterTransportException(status.asFailure(),
                    "fake printer %s is %s".formatted(id, status));
        }
        if (writeFailuresLeft > 0) {
            writeFailuresLeft--;
            PrintFailure failure = writeFailure;
            if (failure == PrintFailure.MID_PRINT) {
                // Half a ticket really is hanging out of the printer: keep the fragment so a test
                // can tell a mid-print failure apart from one that never started.
                int upTo = Math.max(1, bytes.length / MID_PRINT_FRACTION);
                byte[] fragment = new byte[upTo];
                System.arraycopy(bytes, 0, fragment, 0, upTo);
                partials.add(fragment);
            }
            throw new PrinterTransportException(failure,
                    "fake printer %s failed this write (%s)".formatted(id, failure));
        }
        printed.add(bytes.clone());
        log.debug("fake printer {} accepted {} bytes", id, bytes.length);
    }

    // ---- test hooks -----------------------------------------------------------------------

    /** What the next DLE EOT poll answers. */
    public synchronized void setStatus(PrinterStatus next) {
        this.status = next;
    }

    /** Fail the next {@code times} writes with {@code failure}, then behave. */
    public synchronized void failWrites(int times, PrintFailure failure) {
        this.writeFailuresLeft = times;
        this.writeFailure = failure;
    }

    /** Every complete ticket this port has printed, oldest first. */
    public synchronized List<byte[]> printed() {
        return List.copyOf(printed);
    }

    /** The fragments left in the printer by mid-print failures. */
    public synchronized List<byte[]> partials() {
        return List.copyOf(partials);
    }

    public synchronized void reset() {
        printed.clear();
        partials.clear();
        status = PrinterStatus.ONLINE;
        writeFailure = null;
        writeFailuresLeft = 0;
    }
}
