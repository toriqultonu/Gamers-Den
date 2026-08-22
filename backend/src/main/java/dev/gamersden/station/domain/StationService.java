package dev.gamersden.station.domain;

import dev.gamersden.common.error.ConflictException;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.spi.SessionLookup;
import dev.gamersden.common.spi.StationReservation;
import dev.gamersden.station.repo.StationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stations CRUD (api-contract.md, "Stations &amp; pricing" — Admin writes) plus the Floor read:
 * {@code GET /stations} carries the live session summary so one call fills every card.
 *
 * <p>The arrival half of that summary lands with B16 (checked-in bookings); it is already shaped
 * in {@link StationSummary}.
 *
 * <p>A station is never touched while it is occupied: the live session is read through
 * {@link SessionLookup} and the tournament hold through {@link StationReservation}, never through
 * a foreign repository (ARCHITECTURE.md, package layout).
 */
@Service
public class StationService {

    private static final Logger log = LoggerFactory.getLogger(StationService.class);
    private static final Sort BY_ID = Sort.by(Sort.Direction.ASC, "id");

    private final StationRepository stations;
    private final SessionLookup sessions;
    private final StationReservation reservations;

    public StationService(StationRepository stations, SessionLookup sessions,
                          StationReservation reservations) {
        this.stations = stations;
        this.sessions = sessions;
        this.reservations = reservations;
    }

    /**
     * One query for the stations, one for the live sessions and one for the tournament holds — the
     * Floor grid stays a single read however many cards it draws.
     */
    @Transactional(readOnly = true)
    public List<StationSummary> floor() {
        Map<Long, SessionLookup.LiveSession> live = sessions.liveSessionsByStation();
        Set<Long> reserved = reservations.reservedStationIds();
        return stations.findAll(BY_ID).stream()
                .map(station -> StationSummary.of(station, live.get(station.getId()),
                        reserved.contains(station.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public StationSummary summary(Long id) {
        Station station = get(id);
        return StationSummary.of(station, sessions.liveSessionOn(station.getId()).orElse(null),
                reservations.reservedStationIds().contains(station.getId()));
    }

    @Transactional(readOnly = true)
    public Station get(Long id) {
        return stations.findById(id).orElseThrow(() -> new NotFoundException("Station", id));
    }

    @Transactional
    public Station create(String name, ConsoleType consoleType) {
        String trimmed = name.trim();
        if (stations.existsByName(trimmed)) {
            throw duplicateName(trimmed);
        }
        Station created = stations.save(new Station(trimmed, consoleType));
        log.info("station {} created as {} {}", created.getId(), consoleType, trimmed);
        return created;
    }

    /**
     * Every field optional. Renaming is always allowed; changing the console type or pulling the
     * seat off the floor is not, while somebody is still on it — the rate would move under a
     * running session's next block, and the card would lie about the seat being empty.
     */
    @Transactional
    public Station update(Long id, String name, ConsoleType consoleType, StationStatus status) {
        Station station = get(id);
        if (name != null) {
            String trimmed = name.trim();
            if (!trimmed.equals(station.getName()) && stations.existsByName(trimmed)) {
                throw duplicateName(trimmed);
            }
            station.setName(trimmed);
        }
        if (consoleType != null && consoleType != station.getConsoleType()) {
            requireFree(station, "console type");
            station.setConsoleType(consoleType);
        }
        if (status != null && status != station.getStatus()) {
            if (status == StationStatus.MAINTENANCE) {
                requireFree(station, "status");
            }
            station.setStatus(status);
        }
        log.info("station {} updated", id);
        return station;
    }

    /**
     * A real delete — {@code stations} has no active flag, so the row goes only while nothing
     * points at it. A live session or any past one keeps it, and so does any tournament that ever
     * blocked it: sessions and blocks, and later bookings, are the audit trail (409
     * {@code STATION_IN_USE}).
     */
    @Transactional
    public void delete(Long id) {
        Station station = get(id);
        requireFree(station, "delete");
        if (sessions.hasSessionHistory(station.getId())) {
            throw new ConflictException(ErrorCode.STATION_IN_USE,
                    "%s has session history and cannot be removed — put it under maintenance instead"
                            .formatted(station.getName()),
                    Map.of("stationId", station.getId()));
        }
        if (reservations.isBlockedByAnyTournament(station.getId())) {
            throw new ConflictException(ErrorCode.STATION_IN_USE,
                    "%s is blocked for a tournament and cannot be removed — put it under "
                            + "maintenance instead".formatted(station.getName()),
                    Map.of("stationId", station.getId()));
        }
        stations.delete(station);
        log.info("station {} deleted", id);
    }

    private void requireFree(Station station, String what) {
        sessions.liveSessionOn(station.getId()).ifPresent(session -> {
            throw new ConflictException(ErrorCode.STATION_IN_USE,
                    "%s has a live session — %s cannot change until it ends"
                            .formatted(station.getName(), what),
                    Map.of("stationId", station.getId(), "sessionId", session.id()));
        });
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException(ErrorCode.DUPLICATE_NAME,
                "Station name \"%s\" is already taken".formatted(name), Map.of("name", name));
    }
}
