package dev.gamersden.member.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.gamersden.support.AbstractApiIntegrationTest;
import dev.gamersden.support.FloorFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /members}, {@code POST /members}, {@code GET /members/{id}} — the S6 directory. The
 * load-bearing rule is that the phone is the identity: it is stored normalised, searched on
 * digits, and a second registration of the same number is 409 {@code DUPLICATE_PHONE}.
 */
class MemberDirectoryIT extends AbstractApiIntegrationTest {

    private static final String CASHIER_PIN = "4321";

    private FloorFixtures floor;
    private HttpHeaders cashier;

    @BeforeEach
    void seedFloorFixtures() {
        floor = new FloorFixtures(jdbc);
        cashier = null;
    }

    // ---- registering ------------------------------------------------------------------------

    @Test
    void aMemberIsRegisteredWithAnEmptyWalletAndNoPoints() {
        ResponseEntity<JsonNode> created = post("/api/v1/members",
                Map.of("name", "Rafi Ahmed", "phone", "01712345678",
                        "preferredConsole", "PS5", "games", List.of("FIFA 24", "Tekken 8")),
                adminBearer());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        assertThat(body.get("name").asText()).isEqualTo("Rafi Ahmed");
        assertThat(body.get("phone").asText()).isEqualTo("01712345678");
        assertThat(body.get("preferredConsole").asText()).isEqualTo("PS5");
        assertThat(games(body)).containsExactly("FIFA 24", "Tekken 8");
        assertThat(body.get("wallet").asInt()).isZero();
        assertThat(body.get("points").asInt()).isZero();
        assertThat(body.get("createdAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("the phone is stored normalised, so the same number typed twice is one member")
    void duplicatePhoneIsRejected() {
        assertThat(register("Rafi Ahmed", "01712345678").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> again = register("Rafi (again)", "017 1234-5678");

        assertErrorEnvelope(again, 409, "DUPLICATE_PHONE");
        assertThat(again.getBody().get("error").get("details").get("phone").asText())
                .isEqualTo("01712345678");
        assertThat(memberCount()).isEqualTo(1);
    }

    @Test
    void aMemberNeedsBothANameAndAPhone() {
        assertErrorEnvelope(post("/api/v1/members", Map.of("name", "Rafi"), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(post("/api/v1/members", Map.of("phone", "01712345678"), adminBearer()),
                400, "VALIDATION_FAILED");
        assertErrorEnvelope(register("Rafi", "no digits at all"), 400, "VALIDATION_FAILED");
        assertThat(memberCount()).isZero();
    }

    @Test
    @DisplayName("every operator registers members — the matrix grants it to cashiers too")
    void aCashierMayRegisterAMember() {
        ResponseEntity<JsonNode> created = post("/api/v1/members",
                Map.of("name", "Rafi Ahmed", "phone", "01712345678"), cashierBearer());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anAnonymousCallerSeesNothing() {
        assertThat(get("/api/v1/members", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- searching --------------------------------------------------------------------------

    @Test
    @DisplayName("one box over name and phone; a phone matches whatever separators were typed")
    void searchSpansNameAndPhone() {
        register("Rafi Ahmed", "01712345678");
        register("Tanvir Hasan", "01898765432");

        assertThat(namesFound("rafi")).containsExactly("Rafi Ahmed");
        assertThat(namesFound("AHMED")).containsExactly("Rafi Ahmed");
        assertThat(namesFound("0189-8765")).containsExactly("Tanvir Hasan");
        assertThat(namesFound("017 1234 5678")).containsExactly("Rafi Ahmed");
        assertThat(namesFound("Zubair")).isEmpty();
    }

    @Test
    @DisplayName("a term with no digits never falls through to the phone half")
    void aNameTermDoesNotMatchEveryPhone() {
        register("Rafi Ahmed", "01712345678");
        register("Tanvir Hasan", "01898765432");

        assertThat(namesFound("Tanvir")).containsExactly("Tanvir Hasan");
    }

    @Test
    void noQueryListsTheDirectoryByNameInsideThePageEnvelope() {
        register("Tanvir Hasan", "01898765432");
        register("rafi ahmed", "01712345678");

        JsonNode page = get("/api/v1/members", adminBearer()).getBody();

        assertThat(page.get("content").findValuesAsText("name"))
                .containsExactly("rafi ahmed", "Tanvir Hasan");   // case-insensitive sort
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(50);
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);
    }

    // ---- detail -----------------------------------------------------------------------------

    @Test
    @DisplayName("the detail carries the member's recent seats, newest first, station named")
    void detailListsRecentVisits() {
        long memberId = registerId("Rafi Ahmed", "01712345678");
        Long shiftId = floor.openShift(adminId, TERMINAL);
        Long ps5 = createStation("PS5-01", "PS5");
        Long ps4 = createStation("PS4-02", "PS4");
        long older = attachedSession(floor.closedSessionOn(ps4, shiftId), memberId);
        long newer = attachedSession(floor.runningSessionOn(ps5, shiftId, 2, 80, 600), memberId);

        JsonNode detail = get("/api/v1/members/" + memberId, adminBearer()).getBody();

        assertThat(detail.get("id").asLong()).isEqualTo(memberId);
        JsonNode visits = detail.get("visits");
        assertThat(visits).hasSize(2);
        assertThat(visits.get(0).get("sessionId").asLong()).isEqualTo(newer);
        assertThat(visits.get(0).get("stationName").asText()).isEqualTo("PS5-01");
        assertThat(visits.get(0).get("consoleType").asText()).isEqualTo("PS5");
        assertThat(visits.get(0).get("state").asText()).isEqualTo("RUNNING");
        assertThat(visits.get(0).get("blocks").asInt()).isEqualTo(2);
        assertThat(visits.get(0).get("playedSeconds").asLong()).isGreaterThanOrEqualTo(600);
        assertThat(visits.get(0).has("endedAt")).isFalse();          // still on the floor
        assertThat(visits.get(1).get("sessionId").asLong()).isEqualTo(older);
        assertThat(visits.get(1).get("stationName").asText()).isEqualTo("PS4-02");
        assertThat(visits.get(1).get("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    void aMemberWhoHasNeverSatDownHasAnEmptyVisitsStrip() {
        long memberId = registerId("Rafi Ahmed", "01712345678");

        JsonNode detail = get("/api/v1/members/" + memberId, adminBearer()).getBody();

        assertThat(detail.get("visits")).isEmpty();
        assertThat(detail.get("wallet").asInt()).isZero();
    }

    @Test
    void anUnknownMemberIsTheNotFoundEnvelope() {
        assertErrorEnvelope(get("/api/v1/members/999999", adminBearer()), 404, "NOT_FOUND");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private ResponseEntity<JsonNode> register(String name, String phone) {
        return post("/api/v1/members", Map.of("name", name, "phone", phone), adminBearer());
    }

    private long registerId(String name, String phone) {
        ResponseEntity<JsonNode> created = register(name, phone);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private List<String> namesFound(String q) {
        String query = java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
        ResponseEntity<JsonNode> found = get("/api/v1/members?q=" + query, adminBearer());
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        return found.getBody().get("content").findValuesAsText("name");
    }

    private static List<String> games(JsonNode member) {
        List<String> games = new java.util.ArrayList<>();
        member.get("games").forEach(game -> games.add(game.asText()));
        return games;
    }

    private int memberCount() {
        return jdbc.queryForObject("SELECT count(*) FROM members", Integer.class);
    }

    /** Sessions are seated through JDBC here — attaching a member to one is B06's column. */
    private long attachedSession(Long sessionId, long memberId) {
        jdbc.update("UPDATE sessions SET member_id = ? WHERE id = ?", memberId, sessionId);
        return sessionId;
    }

    private Long createStation(String name, String consoleType) {
        ResponseEntity<JsonNode> created = post("/api/v1/stations",
                Map.of("name", name, "consoleType", consoleType), adminBearer());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("id").asLong();
    }

    private HttpHeaders cashierBearer() {
        if (cashier == null) {
            Map<String, Object> body = new HashMap<>(
                    Map.of("name", "Rafi", "role", "CASHIER", "pin", CASHIER_PIN));
            ResponseEntity<JsonNode> created = post("/api/v1/staff", body, adminBearer());
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            cashier = bearerFor(created.getBody().get("id").asLong(), CASHIER_PIN);
        }
        return cashier;
    }
}
