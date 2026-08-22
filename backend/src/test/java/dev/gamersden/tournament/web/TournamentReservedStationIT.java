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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reserved consoles (docs/tournaments.md §2, §4). A station is reserved <em>iff</em> a tournament
 * that is OPEN or LIVE lists it in {@code tournament_station_blocks} — nothing is stored on the
 * station itself, so the Floor card, the walk-in guard and the release all follow from one fact.
 */
class TournamentReservedStationIT extends AbstractApiIntegrationTest {

    private FloorFixtures floor;
    private TournamentFixtures fixtures;
    private HttpHeaders staff;
    private Long reserved;
    private Long free;
    private Long tournamentId;

    @BeforeEach
    void seed() {
        floor = new FloorFixtures(jdbc);
        fixtures = new TournamentFixtures(jdbc);
        staff = adminBearer();
        reserved = createStation("PS5-01", "PS5");
        free = createStation("PS5-02", "PS5");
        floor.openShift(adminId, TERMINAL);
        tournamentId = fixtures.openTournament("Friday FIFA", 200, 8, adminId);
        fixtures.block(tournamentId, reserved);
    }

    @Test
    @DisplayName("a walk-in cannot be seated on a console a running event is holding")
    void walkInOnAReservedStationIsRefused() {
        ResponseEntity<JsonNode> refused = post("/api/v1/sessions",
                Map.of("stationId", reserved), staff);

        assertErrorEnvelope(refused, 409, "STATION_RESERVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isZero();

        // The seat next to it is untouched.
        assertThat(post("/api/v1/sessions", Map.of("stationId", free), staff).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a LIVE event holds its consoles just as an OPEN one does")
    void liveAlsoHolds() {
        fixtures.setStatus(tournamentId, "LIVE");

        assertErrorEnvelope(post("/api/v1/sessions", Map.of("stationId", reserved), staff),
                409, "STATION_RESERVED");
    }

    @Test
    @DisplayName("finishing or cancelling the event releases the console with no second write")
    void statusIsTheWholeReleaseMechanism() {
        fixtures.setStatus(tournamentId, "DONE");

        assertThat(post("/api/v1/sessions", Map.of("stationId", reserved), staff).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // The block row is still there — it is the record of what was held (docs/tournaments.md §2).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tournament_station_blocks",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the Floor draws a held console as RESERVED and an idle one as FREE")
    void floorShowsReserved() {
        JsonNode cards = get("/api/v1/stations", staff).getBody();

        assertThat(floorStateOf(cards, reserved)).isEqualTo("RESERVED");
        assertThat(floorStateOf(cards, free)).isEqualTo("FREE");
        assertThat(get("/api/v1/stations/" + reserved, staff).getBody()
                .get("floorState").asText()).isEqualTo("RESERVED");
        // The match tag and its countdown arrive with B14's console assignment.
        assertThat(get("/api/v1/stations/" + reserved, staff).getBody().has("match")).isFalse();
    }

    @Test
    @DisplayName("a live session outranks the block on the card — nobody is shown out of their seat")
    void aLiveSessionStillWinsTheCard() {
        // Blocked after the session started, which the manager is allowed to do (§4).
        Long other = createStation("PS4-01", "PS4");
        Long shiftId = jdbc.queryForObject("SELECT id FROM shifts WHERE closed_at IS NULL",
                Long.class);
        floor.runningSessionOn(other, shiftId, 2, 50, 0);
        fixtures.block(tournamentId, other);

        assertThat(floorStateOf(get("/api/v1/stations", staff).getBody(), other))
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a console a tournament ever blocked cannot be deleted out from under it")
    void deletingABlockedStationIsRefused() {
        assertErrorEnvelope(delete("/api/v1/stations/" + reserved, staff), 409, "STATION_IN_USE");

        fixtures.setStatus(tournamentId, "CANCELLED");
        assertErrorEnvelope(delete("/api/v1/stations/" + reserved, staff), 409, "STATION_IN_USE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stations WHERE id = ?", Integer.class,
                reserved)).isEqualTo(1);
    }

    @Test
    @DisplayName("two events may hold the same console — each draws only from its own rows")
    void concurrentEventsMayOverlap() {
        Long second = fixtures.openTournament("Saturday Tekken", 300, 8, adminId);

        ResponseEntity<JsonNode> blocked = put("/api/v1/tournaments/" + second + "/blocks",
                Map.of("stationIds", List.of(reserved)), staff);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tournament_station_blocks "
                + "WHERE station_id = ?", Integer.class, reserved)).isEqualTo(2);
    }

    private static String floorStateOf(JsonNode cards, Long stationId) {
        for (JsonNode card : cards) {
            if (card.get("id").asLong() == stationId) {
                return card.get("floorState").asText();
            }
        }
        throw new AssertionError("no card for station " + stationId);
    }

    private Long createStation(String name, String consoleType) {
        return post("/api/v1/stations", Map.of("name", name, "consoleType", consoleType), staff)
                .getBody().get("id").asLong();
    }
}
