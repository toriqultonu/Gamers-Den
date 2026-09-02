package dev.gamersden.sync.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.SyncOutboxWriter;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.BookingFixtures;
import dev.gamersden.support.FakeCloud;
import dev.gamersden.support.MutableClock;
import dev.gamersden.support.MutableClockConfig;
import dev.gamersden.support.TournamentFixtures;
import dev.gamersden.sync.domain.SyncPusher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release gate (TASKLIST B22, ARCHITECTURE.md §7): one evening of trading, driven entirely
 * through the HTTP API against a real Postgres, with a real cloud on the other end of the sync
 * pusher.
 *
 * <p>Every other suite proves one thing in isolation. This one proves they compose — that the
 * money path holds together end to end and that the outbox is a faithful shadow of it:
 *
 * <ol>
 *   <li>a shift opens and a walk-in seat starts buying half hours and drinks;</li>
 *   <li>four tournament tickets sell on one receipt, the fourth draws the bracket, and the event
 *       is played out to a champion;</li>
 *   <li>a booking is sold, cancelled for a full refund, sold again, checked in for a token,
 *       seated from the Floor and <strong>ends without anybody paying twice</strong>;</li>
 *   <li>a play ticket sells while every console is busy, then takes the seat that just freed;</li>
 *   <li>the walk-in bill is split across wallet and cash, the seat closes, and the drawer is
 *       counted short — Z lines for tournament and pre-booking included;</li>
 *   <li>and the cloud, which was <strong>switched off for the middle of all that</strong>,
 *       receives every op exactly once and in order the moment it comes back.</li>
 * </ol>
 *
 * <p>The arithmetic is fixed and checked rather than trusted: 2,000 float, 1,400 taken of which
 * 1,200 in cash, 300 paid out of the drawer, 2,860 counted — 40 short, and an alert to say so.
 *
 * <p>The clock is parked at 18:00 Dhaka so the morning discount is out of the picture and a PS5
 * half hour costs the plain 80 (the window itself belongs to the pricing suite; here it is a
 * constant).
 */
@Import(MutableClockConfig.class)
class MoneyPathE2EIT extends AbstractApiIntegrationTest {

    private static final String SYNC_TOKEN = "e2e-shared-sync-secret";

    /** Started before the context, so the pusher can be pointed at it (see below). */
    private static final FakeCloud CLOUD = FakeCloud.start(SYNC_TOKEN);

    private static final int PS5_HALF_HOUR = 80;
    private static final int PS4_HALF_HOUR = 50;
    private static final int PACKAGE_FEE = 100;
    private static final int ENTRY_FEE = 200;
    private static final int COKE = 30;
    private static final int FLOAT = 2000;
    private static final LocalTime EVENING = LocalTime.of(18, 0);

    /**
     * The venue is told where its mirror is. {@code push-enabled} stays off: the scheduled tick is
     * not what is under test, and a background thread would race every count below, so the suite
     * drives {@link SyncPusher#drain()} by hand — the way the print queue and the session lock
     * sweeper are driven. A small batch size on purpose, so a drain has to loop.
     */
    @DynamicPropertySource
    static void cloudEndpoint(DynamicPropertyRegistry registry) {
        registry.add("gamersden.sync.url", CLOUD::baseUrl);
        registry.add("gamersden.sync.token", () -> SYNC_TOKEN);
        registry.add("gamersden.sync.batch-size", () -> 10);
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private SyncPusher pusher;

    private BookingFixtures bookings;
    private TournamentFixtures tournaments;
    private HttpHeaders staff;

    private Long ps5a;
    private Long ps5b;
    private Long ps4;

    @BeforeEach
    void openTheVenue() {
        clock.setToVenueTime(LocalDate.now(VenueTime.ZONE), EVENING);
        bookings = new BookingFixtures(jdbc);
        tournaments = new TournamentFixtures(jdbc);
        staff = adminBearer();
        ps5a = createStation("PS5-01", "PS5");
        ps5b = createStation("PS5-02", "PS5");
        ps4 = createStation("PS4-01", "PS4");
    }

    @Test
    @DisplayName("an evening of trading reconciles, and the cloud misses none of it")
    void theWholeMoneyPath() {
        // ---- 1. the till opens -----------------------------------------------------------------
        long shiftId = openShift();
        long memberId = createMember("Rifat Hasan", "01711000001");
        long coke = createItem("Coca-Cola 250ml", COKE, 48);

        // ---- 2. a walk-in seat: three half hours and two drinks ---------------------------------
        long walkIn = openSession(ps5a, memberId);
        buyBlock(walkIn);
        buyBlock(walkIn);
        buyBlock(walkIn);
        assertThat(post("/api/v1/sessions/" + walkIn + "/clock", Map.of("action", "START"), staff)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        long cart = openSessionCart(walkIn);
        putLine(cart, coke, 2);

        JsonNode bill = get("/api/v1/sessions/" + walkIn + "/bill", staff).getBody();
        assertThat(bill.get("gamingDue").asInt()).isEqualTo(3 * PS5_HALF_HOUR);
        assertThat(bill.get("fnbDue").asInt()).isEqualTo(2 * COKE);
        assertThat(bill.get("netTotal").asInt()).isEqualTo(300);

        // The cloud is up for this first stretch, so the opening moves are already mirrored.
        assertThat(pusher.drain()).isPositive();
        assertThat(CLOUD.typesOf(SyncOutboxWriter.SHIFTS)).containsExactly(SyncOutboxWriter.OPENED);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.SESSIONS))
                .containsExactly(SyncOutboxWriter.OPENED, SyncOutboxWriter.BLOCKS_CHANGED,
                        SyncOutboxWriter.BLOCKS_CHANGED, SyncOutboxWriter.BLOCKS_CHANGED);
        assertThat(statusOf("state")).isEqualTo("SYNCED");

        // ---- 3. THE UPLINK DROPS. The venue keeps trading. ---------------------------------------
        CLOUD.goOffline();
        int mirroredBeforeTheOutage = CLOUD.ops().size();

        // ---- 4. a tournament, sold out on one receipt and played to a champion --------------------
        long tournamentId = createTournament("Friday FIFA Cup", ENTRY_FEE, 4);
        blockConsole(tournamentId, ps5b);
        long entrySale = sellEntries(tournamentId, "Rifat", "Nafis", "Tanvir", "Shuvo");
        assertThat(tournaments.statusOf(tournamentId))
                .as("the fourth ticket fills the cap and draws the bracket inside its own settle")
                .isEqualTo("LIVE");
        assertThat(bookings.transaction(entrySale)).containsEntry("tournament_amount", 4 * ENTRY_FEE);

        List<Map<String, Object>> bracket = tournaments.matchesOf(tournamentId);
        assertThat(bracket).hasSize(3);
        long championEntry = playOut(tournamentId, bracket);
        assertThat(tournaments.statusOf(tournamentId)).isEqualTo("DONE");
        assertThat(tournaments.winnerEntryOf(tournamentId)).isEqualTo(championEntry);

        // The 30-second tick comes round mid-outage and finds nobody home. The batch is not
        // stamped, the chip goes OFFLINE, and nothing on the counter notices — the venue is fully
        // operational with the cloud down (docs/backend-architecture.md §11).
        int pushesBeforeTheTick = CLOUD.pushCalls();
        assertThat(pusher.drain()).isZero();
        assertThat(CLOUD.pushCalls()).as("it did try").isGreaterThan(pushesBeforeTheTick);
        assertThat(CLOUD.ops()).hasSize(mirroredBeforeTheOutage);
        assertThat(statusOf("state")).isEqualTo("OFFLINE");

        // ---- 5. a booking sold, called off, sold again --------------------------------------------
        long calledOff = book("Tanvir Ahmed", ps4, 2);
        ResponseEntity<JsonNode> cancelled = post("/api/v1/bookings/" + calledOff + "/cancel",
                Map.of("reason", "Customer called off"), withKey());
        assertThat(cancelled.getStatusCode()).as("cancel refused: %s", cancelled.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("refundAmount").asInt())
                .as("outside the cutoff, the whole thing comes back — as a negative transaction")
                .isEqualTo(-(2 * PS4_HALF_HOUR + PACKAGE_FEE));
        assertThat(bookings.statusOf(calledOff)).isEqualTo("CANCELLED");

        long booked = book("Tanvir Ahmed", ps4, 2);

        // ---- 6. check in, take the token, seat it from the Floor ----------------------------------
        JsonNode checkedIn = post("/api/v1/bookings/" + booked + "/check-in", null, staff).getBody();
        long bookingToken = checkedIn.get("token").get("queueEntryId").asLong();
        assertThat(checkedIn.get("token").get("tokenNo").asInt()).isEqualTo(1);
        assertThat(bookings.statusOf(booked)).isEqualTo("ARRIVED");

        long bookedSeat = seat(bookingToken, ps4);
        assertThat(bookings.statusOf(booked)).isEqualTo("USED");
        long bookingSaleTx = ((Number) bookings.booking(booked).get("tx_id")).longValue();
        assertThat(bookings.blocksOf(bookedSeat)).hasSize(2)
                .allSatisfy(block -> assertThat(block)
                        .as("prepaid blocks are born paid, carrying the sale that took the money")
                        .containsEntry("price", PS4_HALF_HOUR)
                        .containsEntry("paid_tx_id", bookingSaleTx));

        // ---- 7. a play ticket sold while there is nowhere to sit -----------------------------------
        // The venue's only PS4 has the booked customer on it, and PS5-01 has the walk-in. The
        // counter sells the time anyway and hands over a token — that is what the queue rail is
        // for (docs/bookings.md §3): prepaid time is not a seat, it is a place in the line.
        assertThat(freeConsolesOfType("PS4")).isZero();
        JsonNode ticket = sellPlayTicket("PS4", 2, "Shuvo Rahman");
        long playToken = ticket.get("queueTokens").get(0).get("queueEntryId").asLong();
        assertThat(ticket.get("queueTokens").get(0).get("tokenNo").asInt()).isEqualTo(2);

        // The booked seat closes with nothing owed: it was paid for at the counter, once.
        int transactionsBefore = countOf("transactions");
        endSession(bookedSeat);
        assertThat(countOf("transactions")).isEqualTo(transactionsBefore);

        long ticketSeat = seat(playToken, ps4);
        assertThat(bookings.blocksOf(ticketSeat)).hasSize(2)
                .allSatisfy(block -> assertThat(block).containsEntry("price", PS4_HALF_HOUR));
        endSession(ticketSeat);

        // ---- 8. the walk-in bill, split across the wallet and the drawer ----------------------------
        topUpWallet(memberId, 200);
        JsonNode settled = settle(Map.of(
                "target", Map.of("sessionId", walkIn),
                "splits", List.of(Map.of("method", "WALLET", "amount", 200),
                        Map.of("method", "CASH", "amount", 100))));
        long saleTx = settled.get("transactionId").asLong();
        assertThat(bookings.transaction(saleTx))
                .containsEntry("gaming_amount", 3 * PS5_HALF_HOUR)
                .containsEntry("fnb_amount", 2 * COKE)
                .containsEntry("total_due", 300);
        assertThat(bookings.tendersOf(saleTx)).hasSize(2);
        // The drink came off the shelf in the same transaction that took the money.
        assertThat(stockOf(coke)).isEqualTo(46);

        clock.advance(Duration.ofMinutes(10));
        staff = adminBearer();
        endSession(walkIn);

        // ---- 9. petty cash, then count the drawer ----------------------------------------------------
        expense("Cleaning supplies", 300);

        JsonNode x = get("/api/v1/shifts/current/x-report", staff).getBody();
        JsonNode totals = x.get("takings").get("totals");
        assertThat(totals.get("gaming").asInt()).isEqualTo(240);
        assertThat(totals.get("fnb").asInt()).isEqualTo(60);
        assertThat(totals.get("tournament").asInt()).isEqualTo(800);
        // Pre-booking line: 200 sold, 200 refunded, 200 sold again, 100 of play ticket.
        assertThat(totals.get("booking").asInt()).isEqualTo(300);
        assertThat(totals.get("total").asInt()).isEqualTo(1400);
        // Only cash reaches the till: 1,400 less the 200 that came off the wallet.
        assertThat(x.get("cash").get("takings").asInt()).isEqualTo(1200);
        assertThat(x.get("cash").get("expected").asInt()).isEqualTo(FLOAT + 1200 - 300);

        JsonNode z = close(2860, "40 short - checked twice");
        assertThat(z.get("kind").asText()).isEqualTo("Z");
        assertThat(z.get("cash").get("discrepancy").asInt()).isEqualTo(-40);
        assertThat(z.get("takings").get("totals").get("tournament").asInt()).isEqualTo(800);
        assertThat(z.get("takings").get("totals").get("booking").asInt()).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE type = 'CASH_DISCREPANCY'",
                Integer.class)).isEqualTo(1);

        // Paper for every money moment, and none of it lost to the outage.
        assertThat(printJobTypes()).contains("RECEIPT", "PLAY_TICKET", "Z_REPORT");

        // ---- 10. the cloud comes back, and has missed nothing ------------------------------------------
        assertThat(CLOUD.ops()).as("nothing reached the cloud while it was down")
                .hasSize(mirroredBeforeTheOutage);
        assertThat(pusher.drain()).as("still nobody home").isZero();
        assertThat(statusOf("state")).isEqualTo("OFFLINE");
        long owed = pendingOps();
        assertThat(owed).as("a whole evening is waiting to be told").isPositive();

        CLOUD.comeBackOnline();
        assertThat(pusher.drain()).isEqualTo((int) owed);

        assertThat(pendingOps()).isZero();
        assertThat(statusOf("state")).isEqualTo("SYNCED");
        assertThat(CLOUD.duplicates()).as("stamped only on success, so nothing is sent twice")
                .isZero();
        assertThat(CLOUD.ops()).hasSize((int) totalOps());
        assertThat(CLOUD.opIdsInOrder())
                .as("ordered: the cloud replays the venue in the sequence it committed")
                .containsExactlyElementsOf(venueOpIdsInOrder());

        // And the shape of the evening is legible from the mirror alone.
        assertThat(CLOUD.typesOf(SyncOutboxWriter.BOOKINGS)).containsExactly(
                SyncOutboxWriter.CREATED, SyncOutboxWriter.CANCELLED, SyncOutboxWriter.CREATED,
                SyncOutboxWriter.CHECKED_IN, SyncOutboxWriter.USED);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.QUEUE_ENTRIES)).containsExactly(
                SyncOutboxWriter.ISSUED, SyncOutboxWriter.SEATED,
                SyncOutboxWriter.ISSUED, SyncOutboxWriter.SEATED);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.SHIFTS))
                .containsExactly(SyncOutboxWriter.OPENED, SyncOutboxWriter.CLOSED);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.TOURNAMENTS)).containsExactly(
                SyncOutboxWriter.CREATED, SyncOutboxWriter.CONSOLES_BLOCKED,
                SyncOutboxWriter.BRACKET_DRAWN);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.TOURNAMENT_MATCHES)).containsExactly(
                SyncOutboxWriter.STARTED, SyncOutboxWriter.WON,
                SyncOutboxWriter.STARTED, SyncOutboxWriter.WON,
                SyncOutboxWriter.STARTED, SyncOutboxWriter.WON);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.EXPENSES))
                .containsExactly(SyncOutboxWriter.RECORDED);
        assertThat(CLOUD.typesOf(SyncOutboxWriter.TRANSACTIONS)).containsExactly(
                SyncOutboxWriter.SETTLED,   // four tournament entries
                SyncOutboxWriter.SETTLED,   // the booking that was called off
                SyncOutboxWriter.REFUNDED,  // and the money going back out
                SyncOutboxWriter.SETTLED,   // the booking that was kept
                SyncOutboxWriter.SETTLED,   // the walk-up play ticket
                SyncOutboxWriter.SETTLED);  // the walk-in seat, split wallet/cash

        // The Z the cloud holds is the Z that printed — including the two lines the whole booking
        // and tournament era exists to reconcile (invariant §5.7).
        JsonNode closedShift = CLOUD.opsOf(SyncOutboxWriter.SHIFTS).get(1);
        assertThat(closedShift.get("entityId").asLong()).isEqualTo(shiftId);
        JsonNode data = closedShift.get("data");
        assertThat(data.get("expectedCash").asInt()).isEqualTo(2900);
        assertThat(data.get("countedCash").asInt()).isEqualTo(2860);
        assertThat(data.get("discrepancy").asInt()).isEqualTo(-40);
        assertThat(data.get("tournamentTakings").asInt()).isEqualTo(800);
        assertThat(data.get("bookingTakings").asInt()).isEqualTo(300);
        assertThat(data.get("expenses").asInt()).isEqualTo(300);
    }

    // ---- the tournament ---------------------------------------------------------------------------

    /** Start, decide, start, decide, start, decide — down to one player standing. */
    private long playOut(long tournamentId, List<Map<String, Object>> bracket) {
        long semiOne = idOf(bracket.get(0));
        long semiTwo = idOf(bracket.get(1));
        long finalId = idOf(bracket.get(2));

        long firstWinner = ((Number) bracket.get(0).get("entry_a")).longValue();
        long secondWinner = ((Number) bracket.get(1).get("entry_b")).longValue();
        decide(tournamentId, semiOne, firstWinner);
        decide(tournamentId, semiTwo, secondWinner);
        decide(tournamentId, finalId, secondWinner);
        return secondWinner;
    }

    /** One match played the way the floor plays it: on a console, then a result. */
    private void decide(long tournamentId, long matchId, long winnerEntryId) {
        String base = "/api/v1/tournaments/" + tournamentId + "/matches/" + matchId;
        ResponseEntity<JsonNode> started = post(base + "/start", null, staff);
        assertThat(started.getStatusCode()).as("start refused: %s", started.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(started.getBody().get("stationId").asLong()).isEqualTo(ps5b);

        ResponseEntity<JsonNode> won =
                post(base + "/winner", Map.of("winnerEntryId", winnerEntryId), staff);
        assertThat(won.getStatusCode()).as("winner refused: %s", won.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private long createTournament(String name, int entryFee, int cap) {
        ResponseEntity<JsonNode> created = post("/api/v1/tournaments", Map.of(
                "name", name,
                "game", "FIFA 25",
                "cadence", "WEEKLY",
                "scheduledAt", venueNow().plusHours(1).toString(),
                "entryFee", entryFee,
                "prizePool", 5000,
                "maxPlayers", cap,
                "matchDurationMin", 20), staff);
        assertThat(created.getStatusCode()).as("create refused: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("tournament").get("id").asLong();
    }

    private void blockConsole(long tournamentId, Long stationId) {
        assertThat(put("/api/v1/tournaments/" + tournamentId + "/blocks",
                Map.of("stationIds", List.of(stationId)), staff).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private long sellEntries(long tournamentId, String... players) {
        List<Map<String, Object>> lines = new ArrayList<>(players.length);
        for (String player : players) {
            lines.add(Map.of("tournamentId", tournamentId, "playerName", player));
        }
        return settle(Map.of(
                "target", Map.of(),
                "tournamentEntries", lines,
                "splits", List.of(Map.of("method", "CASH", "amount", players.length * ENTRY_FEE))))
                .get("transactionId").asLong();
    }

    // ---- bookings and the queue --------------------------------------------------------------------

    private long book(String name, Long stationId, int blocks) {
        Map<String, Object> request = new HashMap<>();
        request.put("stationId", stationId);
        request.put("name", name);
        request.put("startAt", venueNow().plusDays(1).toString());
        request.put("blocks", blocks);
        request.put("method", "CASH");
        ResponseEntity<JsonNode> created = post("/api/v1/bookings", request, withKey());
        assertThat(created.getStatusCode()).as("booking refused: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("booking").get("total").asInt())
                .isEqualTo(blocks * PS4_HALF_HOUR + PACKAGE_FEE);
        return created.getBody().get("booking").get("id").asLong();
    }

    private JsonNode sellPlayTicket(String consoleType, int blocks, String playerName) {
        ResponseEntity<JsonNode> sold = post("/api/v1/payments", Map.of(
                "target", Map.of(),
                "playTickets", List.of(Map.of("consoleType", consoleType, "blocks", blocks,
                        "playerName", playerName)),
                "splits", List.of(Map.of("method", "CASH", "amount", blocks * PS4_HALF_HOUR))),
                withKey());
        assertThat(sold.getStatusCode()).as("ticket refused: %s", sold.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return sold.getBody();
    }

    private long seat(long queueEntryId, Long stationId) {
        ResponseEntity<JsonNode> seated = post("/api/v1/play-queue/" + queueEntryId + "/seat",
                Map.of("stationId", stationId), staff);
        assertThat(seated.getStatusCode()).as("seat refused: %s", seated.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode session = seated.getBody().get("session");
        assertThat(session.get("netOutstanding").asInt()).isZero();
        return session.get("id").asLong();
    }

    // ---- the floor ----------------------------------------------------------------------------------

    private long openSession(Long stationId, Long memberId) {
        ResponseEntity<JsonNode> opened = post("/api/v1/sessions",
                Map.of("stationId", stationId, "memberId", memberId), staff);
        assertThat(opened.getStatusCode()).as("open refused: %s", opened.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return opened.getBody().get("id").asLong();
    }

    private void buyBlock(long sessionId) {
        assertThat(post("/api/v1/sessions/" + sessionId + "/blocks", Map.of("delta", 1), withKey())
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void endSession(long sessionId) {
        ResponseEntity<JsonNode> ended = post("/api/v1/sessions/" + sessionId + "/end", null, staff);
        assertThat(ended.getStatusCode()).as("end refused: %s", ended.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(ended.getBody().get("state").asText()).isEqualTo("CLOSED");
    }

    private int freeConsolesOfType(String consoleType) {
        int free = 0;
        for (JsonNode card : get("/api/v1/stations", staff).getBody()) {
            if (consoleType.equals(card.get("consoleType").asText())
                    && "FREE".equals(card.get("floorState").asText())) {
                free++;
            }
        }
        return free;
    }

    // ---- the counter ---------------------------------------------------------------------------------

    private long openShift() {
        ResponseEntity<JsonNode> opened =
                post("/api/v1/shifts", Map.of("openingFloat", FLOAT), staff);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return opened.getBody().get("id").asLong();
    }

    private JsonNode close(int countedCash, String note) {
        ResponseEntity<JsonNode> closed = post("/api/v1/shifts/current/close",
                Map.of("countedCash", countedCash, "handoverNote", note), staff);
        assertThat(closed.getStatusCode()).as("close refused: %s", closed.getBody())
                .isEqualTo(HttpStatus.OK);
        return closed.getBody();
    }

    private void expense(String description, int amount) {
        assertThat(post("/api/v1/expenses", Map.of("description", description,
                "category", "SUPPLIES", "amount", amount, "voucher", false), staff)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private JsonNode settle(Map<String, Object> body) {
        ResponseEntity<JsonNode> settled = post("/api/v1/payments", body, withKey());
        assertThat(settled.getStatusCode()).as("settle refused: %s", settled.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return settled.getBody();
    }

    private void topUpWallet(long memberId, int amount) {
        ResponseEntity<JsonNode> topped = post("/api/v1/members/" + memberId + "/wallet/topup",
                Map.of("amount", amount, "method", "CASH"), withKey());
        assertThat(topped.getStatusCode()).as("top-up refused: %s", topped.getBody())
                .isEqualTo(HttpStatus.OK);
    }

    private long createMember(String name, String phone) {
        ResponseEntity<JsonNode> created =
                post("/api/v1/members", Map.of("name", name, "phone", phone), staff);
        assertThat(created.getStatusCode()).as("member refused: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private long createItem(String name, int price, int stock) {
        ResponseEntity<JsonNode> created = post("/api/v1/items", Map.of("name", name,
                "category", "BEVERAGE", "price", price, "stock", stock, "reorderAt", 10), staff);
        assertThat(created.getStatusCode()).as("item refused: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private long openSessionCart(long sessionId) {
        ResponseEntity<JsonNode> opened = post("/api/v1/carts",
                Map.of("type", "SESSION", "sessionId", sessionId), staff);
        assertThat(opened.getStatusCode()).as("cart refused: %s", opened.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return opened.getBody().get("id").asLong();
    }

    private void putLine(long cartId, long itemId, int qty) {
        assertThat(put("/api/v1/carts/" + cartId + "/lines", Map.of("itemId", itemId, "qty", qty),
                staff).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), staff)
                .getBody().get("id").asLong();
    }

    // ---- reading the venue back -------------------------------------------------------------------------

    private String statusOf(String field) {
        return get("/api/v1/sync/status", staff).getBody().get(field).asText();
    }

    private long pendingOps() {
        return jdbc.queryForObject("SELECT count(*) FROM sync_outbox WHERE pushed_at IS NULL",
                Long.class);
    }

    private long totalOps() {
        return jdbc.queryForObject("SELECT count(*) FROM sync_outbox", Long.class);
    }

    private List<String> venueOpIdsInOrder() {
        return jdbc.queryForList("SELECT op ->> 'opId' FROM sync_outbox ORDER BY id", String.class);
    }

    private List<String> printJobTypes() {
        return jdbc.queryForList("SELECT DISTINCT type FROM print_jobs", String.class);
    }

    private int stockOf(long itemId) {
        return jdbc.queryForObject("SELECT stock FROM items WHERE id = ?", Integer.class, itemId);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static long idOf(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private OffsetDateTime venueNow() {
        return OffsetDateTime.ofInstant(clock.instant(), VenueTime.ZONE);
    }

    private HttpHeaders withKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(staff);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }
}
