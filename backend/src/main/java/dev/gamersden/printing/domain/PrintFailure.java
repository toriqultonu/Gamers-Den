package dev.gamersden.printing.domain;

/**
 * What lands in {@code print_jobs.error} when a job gives up — the "specific FAILED error"
 * docs/backend-architecture.md §5 asks for, so S11 shows the operator the thing to fix rather
 * than a stack trace.
 *
 * <p>The distinction that matters most is {@link #MID_PRINT}: the printer accepted part of the
 * ticket before it stopped, so there is half a receipt hanging out of it. §5 is explicit that a
 * retry then reprints the <em>full</em> ticket — "paper duplicates acceptable at a staffed
 * counter; silent loss is not".
 */
public enum PrintFailure {

    /** The device never answered. */
    OFFLINE,

    /** Paper end, either polled before the write or reported during it. */
    PAPER_OUT,

    /** Cover open. */
    COVER_OPEN,

    /** The write started and did not finish — part of the ticket is on paper. */
    MID_PRINT,

    /** Anything else the transport threw. */
    TRANSPORT;

    /** The operator-facing half of the alert a failed job raises. */
    public String describe() {
        return switch (this) {
            case OFFLINE -> "printer did not answer";
            case PAPER_OUT -> "out of paper";
            case COVER_OPEN -> "cover open";
            case MID_PRINT -> "failed part-way through the ticket";
            case TRANSPORT -> "transport error";
        };
    }
}
