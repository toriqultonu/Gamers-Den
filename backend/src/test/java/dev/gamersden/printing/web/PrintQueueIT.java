package dev.gamersden.printing.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.printing.domain.FakePrinterPort;
import dev.gamersden.printing.domain.FakePrinterPortProvider;
import dev.gamersden.printing.domain.PrintFailure;
import dev.gamersden.printing.domain.PrintQueueWorker;
import dev.gamersden.printing.domain.PrinterStatus;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The print queue against a real Postgres and the fake port — B18's worker
 * (docs/backend-architecture.md §5, and the printer rows of the §11 cross-cutting matrix).
 *
 * <p>Every job here is created the way a real one is: by taking money. That matters, because the
 * property under test is not "bytes reach a device" but the relationship between the money and the
 * paper — the transaction commits whatever the printer does (invariant §5.3), and whatever the
 * printer does the bytes never change (invariant §5.5).
 *
 * <p>The worker is driven by hand rather than by its timer. {@code PrintQueueScheduler} is off
 * under the {@code test} profile for the same reason the session lock sweeper is: an assertion
 * about {@code attempts} cannot share a job with a background thread. What is called here is the
 * same method the timer calls.
 */
class PrintQueueIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;

    /** {@code gamersden.printing.max-attempts} — the three the venue runs with. */
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private PrintQueueWorker worker;

    @Autowired
    private FakePrinterPortProvider printers;

    private FakePrinterPort printer;
    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long shiftId;

    /** One station per settle: {@code one_live_session_per_station} allows exactly one seat each. */
    private int seats;

    @BeforeEach
    void seedFloor() {
        printer = printers.port();
        printer.reset();
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        seats = 0;
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    // ---- the happy path ----------------------------------------------------------------------

    @Test
    @DisplayName("a queued ticket prints on the first attempt and goes DONE with the stored bytes")
    void happyPrintReachesDone() {
        long jobId = settleOneBlock();
        assertThat(statusOf(jobId)).isEqualTo("QUEUED");

        assertThat(worker.drain(TERMINAL)).isEqualTo(1);

        Map<String, Object> job = jobRow(jobId);
        assertThat(job).containsEntry("status", "DONE")
                .containsEntry("attempts", 1)
                .containsEntry("error", null);
        assertThat(job.get("completed_at")).isNotNull();
        // What came out of the printer is character-for-character what the job stored — there is
        // no second render anywhere in the path (invariant §5.5).
        assertThat(printer.printed()).hasSize(1);
        assertThat(printer.printed().get(0)).isEqualTo(renderedOf(jobId));
        assertThat(alertCount()).isZero();
    }

    @Test
    @DisplayName("two tickets on one device print in the order they were taken, one at a time")
    void ticketsPrintInCounterOrder() {
        long first = settleOneBlock();
        long second = settleOneBlock();

        assertThat(worker.drain(TERMINAL)).isEqualTo(2);

        assertThat(statusOf(first)).isEqualTo("DONE");
        assertThat(statusOf(second)).isEqualTo("DONE");
        assertThat(printer.printed())
                .containsExactly(renderedOf(first), renderedOf(second));
    }

    // ---- the printer is not there -------------------------------------------------------------

    @Test
    @DisplayName("an offline printer fails the job after three attempts and raises an alert")
    void offlinePrinterFailsAfterThreeAttempts() {
        long jobId = settleOneBlock();
        printer.setStatus(PrinterStatus.OFFLINE);

        worker.drain(TERMINAL);

        Map<String, Object> job = jobRow(jobId);
        assertThat(job).containsEntry("status", "FAILED")
                .containsEntry("attempts", MAX_ATTEMPTS)
                .containsEntry("error", PrintFailure.OFFLINE.name());
        assertThat(job.get("completed_at")).isNotNull();
        // Nothing was pushed at a printer that said it was not there: the DLE EOT poll is what
        // stops a ticket being fired into the dark.
        assertThat(printer.printed()).isEmpty();

        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT type, title, body, read FROM alerts ORDER BY id DESC LIMIT 1");
        assertThat(alert).containsEntry("type", "PRINTER_FAILED").containsEntry("read", false);
        assertThat((String) alert.get("title")).contains("printer did not answer");
        assertThat((String) alert.get("body")).contains(TERMINAL);

        // The money stands. A failed print never unwinds a settle (invariant §5.3).
        assertThat(countOf("transactions")).isEqualTo(1);
    }

    @Test
    @DisplayName("out of paper and cover open are told apart, not lumped into one failure")
    void statusFailuresKeepTheirCause() {
        long outOfPaper = settleOneBlock();
        printer.setStatus(PrinterStatus.OUT_OF_PAPER);
        worker.drain(TERMINAL);
        assertThat(jobRow(outOfPaper)).containsEntry("error", PrintFailure.PAPER_OUT.name());

        long coverOpen = settleOneBlock();
        printer.setStatus(PrinterStatus.COVER_OPEN);
        worker.drain(TERMINAL);
        assertThat(jobRow(coverOpen)).containsEntry("error", PrintFailure.COVER_OPEN.name());
    }

    @Test
    @DisplayName("a printer that recovers on the third attempt still prints, once")
    void transientFailureIsRetriedInsideTheJob() {
        long jobId = settleOneBlock();
        printer.failWrites(2, PrintFailure.TRANSPORT);

        worker.drain(TERMINAL);

        assertThat(jobRow(jobId)).containsEntry("status", "DONE").containsEntry("attempts", 3);
        assertThat(printer.printed()).hasSize(1);
    }

    // ---- half a ticket -------------------------------------------------------------------------

    @Test
    @DisplayName("a mid-print failure retries the whole ticket, byte-identical, and counts up")
    void midPrintFailureRetriesIdenticalBytes() {
        long jobId = settleOneBlock();
        byte[] stored = renderedOf(jobId);
        printer.failWrites(MAX_ATTEMPTS, PrintFailure.MID_PRINT);

        worker.drain(TERMINAL);

        assertThat(jobRow(jobId)).containsEntry("status", "FAILED")
                .containsEntry("attempts", MAX_ATTEMPTS)
                .containsEntry("error", PrintFailure.MID_PRINT.name());
        // Paper really did come out — that is what makes this different from an offline failure,
        // and why the retry below reprints the whole thing rather than resuming.
        assertThat(printer.partials()).isNotEmpty();
        assertThat(printer.printed()).isEmpty();

        ResponseEntity<JsonNode> retried = post("/api/v1/print-jobs/" + jobId + "/retry", null, staff);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().get("status").asText()).isEqualTo("QUEUED");

        worker.drain(TERMINAL);

        Map<String, Object> job = jobRow(jobId);
        assertThat(job).containsEntry("status", "DONE")
                // Three failed plus one that worked: the column is an audit, not a countdown.
                .containsEntry("attempts", MAX_ATTEMPTS + 1)
                .containsEntry("error", null);
        assertThat(printer.printed()).hasSize(1);
        assertThat(printer.printed().get(0))
                .as("the retry re-sends the stored bytes, not a fresh render")
                .isEqualTo(stored);
        assertThat(renderedOf(jobId)).isEqualTo(stored);
    }

    @Test
    @DisplayName("a job that is not FAILED cannot be retried")
    void onlyFailedJobsRetry() {
        long jobId = settleOneBlock();

        assertErrorEnvelope(post("/api/v1/print-jobs/" + jobId + "/retry", null, staff), 409, "CONFLICT");

        worker.drain(TERMINAL);
        assertErrorEnvelope(post("/api/v1/print-jobs/" + jobId + "/retry", null, staff), 409, "CONFLICT");
        assertThat(printer.printed()).hasSize(1);
    }

    // ---- devices are separate queues -------------------------------------------------------------

    @Test
    @DisplayName("draining one device leaves another counter's paper where it was")
    void devicesDrainIndependently() {
        long mine = settleOneBlock();
        long theirs = jdbc.queryForObject(
                "INSERT INTO print_jobs (type, ref_id, device_id, operator_id, rendered, "
                        + "rendered_text) VALUES ('TEST', 0, 'T2', ?, ?, 'test') RETURNING id",
                Long.class, adminId, "test".getBytes());

        assertThat(worker.drain(TERMINAL)).isEqualTo(1);

        assertThat(statusOf(mine)).isEqualTo("DONE");
        assertThat(statusOf(theirs)).isEqualTo("QUEUED");

        assertThat(worker.drainAll()).isEqualTo(1);
        assertThat(statusOf(theirs)).isEqualTo("DONE");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** One settle on one block — the smallest real money write that produces a receipt. */
    private long settleOneBlock() {
        Long stationId = createStation("PS5-%02d".formatted(++seats), "PS5");
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 1, PS5_HALF_HOUR, 0);
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> settled = post("/api/v1/payments",
                Map.of("target", Map.of("sessionId", sessionId),
                        "splits", List.of(Map.of("method", "CASH", "amount", PS5_HALF_HOUR))),
                headers);
        assertThat(settled.getStatusCode()).as("settle failed: %s", settled.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return settled.getBody().get("printJobId").asLong();
    }

    private Map<String, Object> jobRow(long jobId) {
        return jdbc.queryForMap("SELECT status, attempts, error, device_id, completed_at "
                + "FROM print_jobs WHERE id = ?", jobId);
    }

    private String statusOf(long jobId) {
        return jdbc.queryForObject("SELECT status FROM print_jobs WHERE id = ?", String.class, jobId);
    }

    private byte[] renderedOf(long jobId) {
        return jdbc.queryForObject("SELECT rendered FROM print_jobs WHERE id = ?", byte[].class, jobId);
    }

    private int alertCount() {
        return countOf("alerts");
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }
}
