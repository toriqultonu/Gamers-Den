package dev.gamersden.printing.domain;

/**
 * A {@link PrinterPort} write that did not land, carrying which of the four things went wrong so
 * the worker can store it on the job instead of guessing.
 *
 * <p>Not an {@code ApiException}: nothing about a failed print is a failed request. The money is
 * already committed by the time the worker runs (invariant §5.3) — the paper is what failed, and
 * the operator hears about it through the job's status and the alert, not through a 5xx.
 */
public class PrinterTransportException extends RuntimeException {

    private final PrintFailure failure;

    public PrinterTransportException(PrintFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public PrinterTransportException(PrintFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public PrintFailure failure() {
        return failure;
    }
}
