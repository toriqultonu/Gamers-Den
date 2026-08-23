package dev.gamersden.printing.domain;

/**
 * What {@code GET /printers} reports and what a DLE EOT poll resolves to
 * (api-contract.md, "Print jobs": ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN).
 *
 * <p>The three unhappy values are deliberately distinct rather than one "not ready": the operator
 * is standing at the counter with a customer waiting, and "load paper" and "close the lid" are
 * different actions from "the printer is unplugged". They travel all the way to
 * {@code print_jobs.error} as their matching {@link PrintFailure} so S11 can say which.
 */
public enum PrinterStatus {

    ONLINE,

    /** No device, or the device stopped answering — cable, power, or a crashed printer. */
    OFFLINE,

    /** Paper end, from the DLE EOT paper sensor. */
    OUT_OF_PAPER,

    /** Cover open, from the DLE EOT offline-cause byte. */
    COVER_OPEN;

    /** The failure a job takes when this is what the poll said before the bytes went out. */
    public PrintFailure asFailure() {
        return switch (this) {
            case ONLINE -> PrintFailure.TRANSPORT;
            case OFFLINE -> PrintFailure.OFFLINE;
            case OUT_OF_PAPER -> PrintFailure.PAPER_OUT;
            case COVER_OPEN -> PrintFailure.COVER_OPEN;
        };
    }
}
