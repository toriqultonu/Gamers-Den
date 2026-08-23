package dev.gamersden.printing.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.printing.domain.FakePrinterPortProvider;
import dev.gamersden.printing.domain.PrintQueueWorker;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /print-jobs} and {@code /printers} — the endpoints S11 is built on (api-contract.md,
 * "Print jobs"; design.md §5 S11).
 *
 * <p>The through-line of every test here is invariant §5.5: a ticket is rendered once and stored,
 * so the preview is the paper, a retry is the same paper, and a reprint is that same paper under a
 * band with a reason on it. Anything that re-renders would break all three at once.
 */
class PrintJobEndpointsIT extends AbstractApiIntegrationTest {

    private static final int PS5_HALF_HOUR = 80;

    @Autowired
    private PrintQueueWorker worker;

    @Autowired
    private FakePrinterPortProvider printers;

    private FloorFixtures floor;
    private HttpHeaders staff;
    private Long shiftId;

    /** One station per settle: {@code one_live_session_per_station} allows exactly one seat each. */
    private int seats;

    @BeforeEach
    void seedFloor() {
        printers.port().reset();
        floor = new FloorFixtures(jdbc);
        staff = adminBearer();
        seats = 0;
        shiftId = floor.openShift(adminId, TERMINAL);
    }

    // ---- reads ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a job reports its queue state, and its render is the stored 48-column text")
    void jobAndRenderAreReadable() {
        long jobId = settleOneBlock();

        JsonNode job = ok(get("/api/v1/print-jobs/" + jobId, staff));
        assertThat(job.get("type").asText()).isEqualTo("RECEIPT");
        assertThat(job.get("status").asText()).isEqualTo("QUEUED");
        assertThat(job.get("attempts").asInt()).isZero();
        assertThat(job.get("device").asText()).isEqualTo(TERMINAL);
        assertThat(job.get("operatorId").asLong()).isEqualTo(adminId);
        assertThat(job.get("isReprint").asBoolean()).isFalse();
        assertThat(job.has("reprintReason")).isFalse();
        assertThat(job.has("originalJobId")).isFalse();

        JsonNode render = ok(get("/api/v1/print-jobs/" + jobId + "/render", staff));
        assertThat(render.get("columns").asInt()).isEqualTo(48);
        assertThat(render.get("text").asText()).isEqualTo(renderedTextOf(jobId));
        assertThat(render.get("bytes").asInt()).isEqualTo(renderedOf(jobId).length);
    }

    @Test
    @DisplayName("an unknown job is a 404 envelope")
    void unknownJobIsNotFound() {
        assertErrorEnvelope(get("/api/v1/print-jobs/909090", staff), 404, "NOT_FOUND");
    }

    // ---- reprint ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a reprint with no reason is 400 and queues nothing")
    void reprintNeedsAReason() {
        long jobId = settleOneBlock();

        Map<String, Object> noReason = new HashMap<>();
        noReason.put("reason", null);
        assertErrorEnvelope(post("/api/v1/print-jobs/" + jobId + "/reprint", noReason, staff),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/print-jobs/" + jobId + "/reprint", Map.of(), staff),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/print-jobs/" + jobId + "/reprint",
                Map.of("reason", "BECAUSE"), staff), 400, "VALIDATION_FAILED");

        assertThat(countOf("print_jobs")).isEqualTo(1);
    }

    @Test
    @DisplayName("a reprint is a new job: banded, reasoned, linked, and carrying the original bytes")
    void reprintIsANewLinkedJob() {
        long original = settleOneBlock();
        worker.drain(TERMINAL);
        byte[] originalBytes = renderedOf(original);

        JsonNode copy = created(post("/api/v1/print-jobs/" + original + "/reprint",
                Map.of("reason", "CUSTOMER_COPY"), staff));

        long copyId = copy.get("id").asLong();
        assertThat(copyId).isNotEqualTo(original);
        assertThat(copy.get("status").asText()).isEqualTo("QUEUED");
        assertThat(copy.get("isReprint").asBoolean()).isTrue();
        assertThat(copy.get("reprintReason").asText()).isEqualTo("CUSTOMER_COPY");
        assertThat(copy.get("originalJobId").asLong()).isEqualTo(original);
        assertThat(copy.get("type").asText()).isEqualTo("RECEIPT");
        assertThat(copy.get("refId").asLong()).isEqualTo(jobRefId(original));

        // The band is on top and the original ticket is underneath it, unchanged.
        String reprinted = renderedTextOf(copyId);
        assertThat(reprinted).contains("REPRINT").contains("CUSTOMER COPY")
                .endsWith(renderedTextOf(original));

        // The first ticket is untouched: it is what the customer was handed and what the audit says.
        assertThat(renderedOf(original)).isEqualTo(originalBytes);
        assertThat(statusOf(original)).isEqualTo("DONE");

        worker.drain(TERMINAL);
        assertThat(statusOf(copyId)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("reprinting a reprint bands the original again rather than stacking bands")
    void reprintOfAReprintPointsAtTheOriginal() {
        long original = settleOneBlock();
        long first = created(post("/api/v1/print-jobs/" + original + "/reprint",
                Map.of("reason", "LOST"), staff)).get("id").asLong();

        JsonNode second = created(post("/api/v1/print-jobs/" + first + "/reprint",
                Map.of("reason", "DAMAGED"), staff));

        assertThat(second.get("originalJobId").asLong()).isEqualTo(original);
        assertThat(renderedTextOf(second.get("id").asLong()))
                .endsWith(renderedTextOf(original))
                .doesNotContain("LOST");
    }

    @Test
    @DisplayName("a cashier may reprint their own ticket but needs a manager for someone else's")
    void reprintingSomeoneElsesTicketIsManagerPlus() {
        long adminsJob = settleOneBlock();
        Long cashierId = createStaff("Tanvir", "CASHIER", "4321");
        HttpHeaders cashier = bearerFor(cashierId, "4321");

        assertErrorEnvelope(post("/api/v1/print-jobs/" + adminsJob + "/reprint",
                Map.of("reason", "LOST"), cashier), 403, "FORBIDDEN");

        long cashiersJob = jdbc.queryForObject(
                "INSERT INTO print_jobs (type, ref_id, device_id, operator_id, rendered, "
                        + "rendered_text) VALUES ('TEST', 0, ?, ?, ?, 'own ticket') RETURNING id",
                Long.class, TERMINAL, cashierId, "own ticket".getBytes());
        assertThat(created(post("/api/v1/print-jobs/" + cashiersJob + "/reprint",
                Map.of("reason", "LOST"), cashier)).get("isReprint").asBoolean()).isTrue();
    }

    // ---- receipt copies ----------------------------------------------------------------------------

    @Test
    @DisplayName("receipt_copies = 2 puts the copy in the job, after the cut")
    void secondCopyRidesInsideTheSameJob() {
        long single = settleOneBlock();
        String oneCopy = renderedTextOf(single);

        jdbc.update("INSERT INTO terminal_settings (terminal, receipt_copies) VALUES (?, 2) "
                + "ON CONFLICT (terminal) DO UPDATE SET receipt_copies = 2", TERMINAL);
        long doubled = settleOneBlock();

        String twoCopies = renderedTextOf(doubled);
        assertThat(twoCopies).contains("[CUT]");
        // One job, one claim, one write — the copy is bytes, not a second ticket in the queue.
        assertThat(twoCopies.length()).isGreaterThan(oneCopy.length());
        assertThat(countOf("print_jobs")).isEqualTo(2);

        worker.drain(TERMINAL);
        assertThat(printers.port().printed()).hasSize(2);
    }

    // ---- printers -------------------------------------------------------------------------------

    @Test
    @DisplayName("the printers list carries a live status and marks the default")
    void printersListIsLive() {
        JsonNode printerList = ok(get("/api/v1/printers", staff));
        assertThat(printerList.isArray()).isTrue();
        assertThat(printerList.size()).isEqualTo(1);
        JsonNode only = printerList.get(0);
        assertThat(only.get("id").asText()).isEqualTo(FakePrinterPortProvider.DEVICE_ID);
        assertThat(only.get("status").asText()).isEqualTo("ONLINE");
        assertThat(only.get("isDefault").asBoolean()).isTrue();

        printers.port().setStatus(dev.gamersden.printing.domain.PrinterStatus.OUT_OF_PAPER);
        assertThat(ok(get("/api/v1/printers", staff)).get(0).get("status").asText())
                .isEqualTo("OUT_OF_PAPER");
    }

    @Test
    @DisplayName("the default printer is an Admin choice, and must be one that exists")
    void defaultPrinterIsGuarded() {
        Long managerId = createStaff("Shuvo", "MANAGER", "5678");
        assertErrorEnvelope(put("/api/v1/printers/default",
                        Map.of("printerId", FakePrinterPortProvider.DEVICE_ID),
                        bearerFor(managerId, "5678")),
                403, "FORBIDDEN");

        assertErrorEnvelope(put("/api/v1/printers/default", Map.of("printerId", "usb:dead:beef"), staff),
                404, "NOT_FOUND");

        JsonNode chosen = ok(put("/api/v1/printers/default",
                Map.of("printerId", FakePrinterPortProvider.DEVICE_ID), staff));
        assertThat(chosen.get("isDefault").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a test ticket goes through the queue like any other job")
    void testTicketIsAnOrdinaryJob() {
        JsonNode queued = created(post(
                "/api/v1/printers/" + FakePrinterPortProvider.DEVICE_ID + "/test", null, staff));

        long jobId = queued.get("id").asLong();
        assertThat(queued.get("type").asText()).isEqualTo("TEST");
        assertThat(queued.get("status").asText()).isEqualTo("QUEUED");
        assertThat(queued.get("device").asText()).isEqualTo(TERMINAL);
        assertThat(renderedTextOf(jobId)).contains("PRINTER TEST")
                .contains(FakePrinterPortProvider.DEVICE_ID);

        worker.drain(TERMINAL);
        assertThat(statusOf(jobId)).isEqualTo("DONE");
        assertThat(printers.port().printed()).hasSize(1);

        assertErrorEnvelope(post("/api/v1/printers/usb:dead:beef/test", null, staff), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("print endpoints need a signed-in operator")
    void anonymousIsRejected() {
        assertThat(get("/api/v1/printers", null).getStatusCode().value()).isEqualTo(401);
        assertThat(get("/api/v1/print-jobs/1", null).getStatusCode().value()).isEqualTo(401);
    }

    // ---- helpers ----------------------------------------------------------------------------------

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

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static JsonNode created(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).as("%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Long createStaff(String name, String role, String pin) {
        ResponseEntity<JsonNode> response = post("/api/v1/staff",
                Map.of("name", name, "role", role, "pin", pin), staff);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), staff);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private String statusOf(long jobId) {
        return jdbc.queryForObject("SELECT status FROM print_jobs WHERE id = ?", String.class, jobId);
    }

    private long jobRefId(long jobId) {
        return jdbc.queryForObject("SELECT ref_id FROM print_jobs WHERE id = ?", Long.class, jobId);
    }

    private String renderedTextOf(long jobId) {
        return jdbc.queryForObject("SELECT rendered_text FROM print_jobs WHERE id = ?",
                String.class, jobId);
    }

    private byte[] renderedOf(long jobId) {
        return jdbc.queryForObject("SELECT rendered FROM print_jobs WHERE id = ?", byte[].class, jobId);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
