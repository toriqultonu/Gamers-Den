package dev.gamersden.tournament.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import dev.gamersden.support.TournamentFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancelling an event (docs/tournaments.md §3). One transaction does three things: the status
 * moves to CANCELLED, every console it was holding is released by that alone, and every entry that
 * still owed money is refunded — as negative transactions posted to the shift doing the cancelling,
 * through the methods the money came in by (invariant §5.7).
 */
class TournamentCancelRefundIT extends AbstractApiIntegrationTest {

    private static final int FEE = 200;

    private FloorFixtures floor;
    private TournamentFixtures fixtures;
    private HttpHeaders manager;
    private Long shiftId;
    private Long stationId;
    private Long tournamentId;

    @BeforeEach
    void seed() {
        floor = new FloorFixtures(jdbc);
        fixtures = new TournamentFixtures(jdbc);
        manager = adminBearer();
        stationId = createStation("PS5-01", "PS5");
        shiftId = floor.openShift(adminId, TERMINAL);
        tournamentId = fixtures.openTournament("Friday FIFA", FEE, 8, adminId);
        fixtures.block(tournamentId, stationId);
    }

    @Test
    @DisplayName("a cancel refunds every entry, one negative transaction per originating sale")
    void cancelRefundsEveryEntry() {
        long saleOne = sellEntries("Rifat", "Nafis");   // 400 on one receipt
        long saleTwo = sellEntries("Tanvir");           // 200 on another

        ResponseEntity<JsonNode> cancelled = post("/api/v1/tournaments/" + tournamentId + "/cancel",
                Map.of("reason", "Not enough players"), manager);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("tournament").get("status").asText())
                .isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("entriesRefunded").asInt()).isEqualTo(3);
        assertThat(cancelled.getBody().get("refunds")).hasSize(2);

        // Every entry is flagged, so nothing can be paid for twice.
        assertThat(fixtures.entriesOf(tournamentId))
                .allSatisfy(entry -> assertThat(entry).containsEntry("refunded", true));

        List<Map<String, Object>> refunds = fixtures.refundsOf(shiftId);
        assertThat(refunds).hasSize(2);
        assertThat(refunds.get(0)).containsEntry("total_due", -2 * FEE)
                .containsEntry("tournament_amount", -2 * FEE);
        assertThat(refunds.get(1)).containsEntry("total_due", -FEE)
                .containsEntry("tournament_amount", -FEE);

        // Negative tenders, same method the fee came in by.
        assertThat(fixtures.tendersOf((Long) refunds.get(0).get("id")))
                .singleElement()
                .satisfies(tender -> assertThat(tender).containsEntry("method", "CASH")
                        .containsEntry("amount", -2 * FEE));

        // The sales themselves are untouched: a refund is a transaction, not an edit.
        assertThat(jdbc.queryForObject("SELECT voided FROM transactions WHERE id = ?",
                Boolean.class, saleOne)).isFalse();
        assertThat(jdbc.queryForObject("SELECT tournament_amount FROM transactions WHERE id = ?",
                Integer.class, saleTwo)).isEqualTo(FEE);

        // The drawer nets out to nothing taken for this event.
        assertThat(jdbc.queryForObject("SELECT sum(tournament_amount) FROM transactions "
                + "WHERE shift_id = ?", Integer.class, shiftId)).isZero();
    }

    @Test
    @DisplayName("the money goes back the way it came, split across the methods that paid")
    void refundsMirrorTheOriginalTenders() {
        settle(Map.of("target", Map.of(),
                "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                "splits", List.of(
                        Map.of("method", "CASH", "amount", 150),
                        Map.of("method", "BKASH", "amount", 50, "paymentRef", "8XK21QW7"))));

        post("/api/v1/tournaments/" + tournamentId + "/cancel", Map.of("reason", "Called off"),
                manager);

        Map<String, Object> refund = fixtures.refundsOf(shiftId).get(0);
        List<Map<String, Object>> back = fixtures.tendersOf((Long) refund.get("id"));
        assertThat(back).hasSize(2);
        assertThat(back).extracting(row -> row.get("amount")).containsExactlyInAnyOrder(-150, -50);
        assertThat(back).extracting(row -> row.get("method"))
                .containsExactlyInAnyOrder("CASH", "BKASH");
        assertThat(back.stream().mapToInt(row -> (int) row.get("amount")).sum()).isEqualTo(-FEE);
    }

    @Test
    @DisplayName("wallet money goes back to the wallet, not into the drawer")
    void walletSharesReturnToTheBalance() {
        Long memberId = createMember("Rifat Hasan", "+8801712448190", 1000);
        Long sessionId = floor.runningSessionOn(stationId, shiftId, 0, 80, 0);
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);

        settle(Map.of("target", Map.of("sessionId", sessionId),
                "tournamentEntries", List.of(Map.of("tournamentId", tournamentId)),
                "splits", List.of(Map.of("method", "WALLET", "amount", FEE))));
        assertThat(walletOf(memberId)).isEqualTo(1000 - FEE);

        post("/api/v1/tournaments/" + tournamentId + "/cancel", Map.of("reason", "Called off"),
                manager);

        assertThat(walletOf(memberId)).isEqualTo(1000);
        Long refundId = (Long) fixtures.refundsOf(shiftId).get(0).get("id");
        assertThat(jdbc.queryForList("SELECT delta, kind, ref_tx_id FROM wallet_ledger "
                + "WHERE ref_tx_id = ?", refundId))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("delta", FEE)
                        .containsEntry("kind", "REVERSAL"));
        // The points the sale earned are the customer's to keep.
        assertThat(jdbc.queryForObject("SELECT points FROM members WHERE id = ?", Integer.class,
                memberId)).isEqualTo(FEE / 20);
    }

    @Test
    @DisplayName("cancelling releases the consoles and stops the event selling")
    void cancelReleasesAndCloses() {
        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", stationId), manager),
                409, "STATION_RESERVED");

        post("/api/v1/tournaments/" + tournamentId + "/cancel", Map.of("reason", "Called off"),
                manager);

        assertThat(post("/api/v1/sessions", Map.of("stationId", stationId), manager)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertErrorEnvelope(post("/api/v1/tournaments/" + tournamentId + "/entries",
                        Map.of("splits", List.of(Map.of("method", "CASH", "amount", FEE))),
                        withKey(UUID.randomUUID().toString())),
                409, "TOURNAMENT_NOT_OPEN");
    }

    @Test
    @DisplayName("a second cancel is 409 and hands nothing back twice")
    void cancellingTwiceIsRefused() {
        sellEntries("Rifat");
        post("/api/v1/tournaments/" + tournamentId + "/cancel", Map.of("reason", "Called off"),
                manager);
        int refundsAfterFirst = fixtures.refundsOf(shiftId).size();

        assertErrorEnvelope(post("/api/v1/tournaments/" + tournamentId + "/cancel",
                Map.of("reason", "Again"), manager), 409, "CONFLICT");
        assertThat(fixtures.refundsOf(shiftId)).hasSize(refundsAfterFirst);
    }

    @Test
    @DisplayName("an event nobody entered cancels cleanly, with no refunds at all")
    void cancellingAnEmptyEventWritesNoMoney() {
        ResponseEntity<JsonNode> cancelled = post("/api/v1/tournaments/" + tournamentId + "/cancel",
                Map.of(), manager);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("entriesRefunded").asInt()).isZero();
        assertThat(cancelled.getBody().get("refunds")).isEmpty();
        assertThat(countOf("transactions")).isZero();
        assertThat(fixtures.statusOf(tournamentId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("a cancel with refunds to make needs an open drawer to make them from")
    void refundsNeedAnOpenShift() {
        sellEntries("Rifat");
        jdbc.update("UPDATE shifts SET closed_at = now() WHERE id = ?", shiftId);

        assertErrorEnvelope(post("/api/v1/tournaments/" + tournamentId + "/cancel",
                Map.of("reason", "Called off"), manager), 409, "CONFLICT");

        // Nothing moved: the whole cancel rolled back with its first refund.
        assertThat(fixtures.statusOf(tournamentId)).isEqualTo("OPEN");
        assertThat(fixtures.entriesOf(tournamentId).get(0)).containsEntry("refunded", false);
        assertThat(fixtures.refundsOf(shiftId)).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** One counter sale covering every named player, and the transaction it wrote. */
    private long sellEntries(String... players) {
        List<Map<String, Object>> entries = java.util.Arrays.stream(players)
                .map(player -> Map.<String, Object>of("tournamentId", tournamentId,
                        "playerName", player))
                .toList();
        return settle(Map.of("target", Map.of(),
                "tournamentEntries", entries,
                "splits", List.of(Map.of("method", "CASH", "amount", players.length * FEE))))
                .get("transactionId").asLong();
    }

    private JsonNode settle(Map<String, Object> body) {
        ResponseEntity<JsonNode> response =
                post("/api/v1/payments", body, withKey(UUID.randomUUID().toString()));
        assertThat(response.getStatusCode()).as("settle failed: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private HttpHeaders withKey(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(manager);
        headers.add("Idempotency-Key", key);
        return headers;
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), manager)
                .getBody().get("id").asLong();
    }

    private Long createMember(String name, String phone, int wallet) {
        return jdbc.queryForObject("INSERT INTO members (name, phone, wallet) VALUES (?, ?, ?) "
                + "RETURNING id", Long.class, name, phone, wallet);
    }

    private int walletOf(Long memberId) {
        return jdbc.queryForObject("SELECT wallet FROM members WHERE id = ?", Integer.class, memberId);
    }

    private int countOf(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
