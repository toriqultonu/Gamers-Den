package dev.gamersden.printing.web;

import dev.gamersden.printing.domain.PrintJob;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * {@code GET /print-jobs/{id}} — "status QUEUED|PRINTING|DONE|FAILED, attempts, device, operator,
 * isReprint, reprintReason?, originalJobId?" (api-contract.md, "Print jobs"), which is exactly the
 * state S11 switches on: rendering, ready, queued, failed (design.md §5).
 *
 * <p>No bytes. The ESC/POS stream is not something a browser can do anything with; the 48-column
 * preview lives behind {@code /render} so a poll of this endpoint stays small.
 *
 * @param error   which failure, when there was one — {@code PAPER_OUT}, {@code COVER_OPEN},
 *                {@code OFFLINE}, {@code MID_PRINT}, {@code TRANSPORT} — so S11 can name the thing
 *                to fix instead of saying "try again"
 * @param device  the queue the job was written to; today the terminal that created it
 */
@Schema(name = "PrintJob", description = "One queued, printed or failed ticket")
public record PrintJobView(long id,
                           String type,
                           long refId,
                           String status,
                           int attempts,
                           String device,
                           long operatorId,
                           boolean isReprint,
                           String reprintReason,
                           Long originalJobId,
                           String error,
                           OffsetDateTime createdAt,
                           OffsetDateTime completedAt) {

    public static PrintJobView of(PrintJob job) {
        return new PrintJobView(job.getId(), job.getType().name(), job.getRefId(),
                job.getStatus().name(), job.getAttempts(), job.getDeviceId(), job.getOperatorId(),
                job.isReprint(),
                job.getReprintReason() == null ? null : job.getReprintReason().name(),
                job.getOriginalJobId(), job.getError(), job.getCreatedAt(), job.getCompletedAt());
    }
}
