package dev.gamersden.station.web;

import dev.gamersden.common.security.Roles;
import dev.gamersden.station.domain.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code /stations} — every operator reads the Floor, only an Admin edits it (api-contract.md,
 * permission matrix: "Stations CRUD, pricing, staff CRUD"). The guard is the {@code @PreAuthorize}
 * here, not the sidebar; a cashier writing gets the 403 envelope.
 */
@RestController
@RequestMapping("/stations")
@Tag(name = "Stations")
public class StationController {

    private final StationService stations;

    public StationController(StationService stations) {
        this.stations = stations;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "The Floor grid",
            description = "Each station with its live session summary. A console held by a "
                    + "running tournament reads RESERVED, and carries its match countdown while "
                    + "one is being played on it. The checked-in arrival half arrives with B16 "
                    + "and is null until then.")
    public List<StationView> list() {
        return stations.floor().stream().map(StationView::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.ANY_STAFF)
    @Operation(summary = "One station card")
    public StationView get(@PathVariable Long id) {
        return StationView.of(stations.summary(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Add a station (Admin)",
            description = "409 DUPLICATE_NAME when the name is taken.")
    public StationView create(@Valid @RequestBody CreateStationRequest request) {
        return StationView.of(stations.summary(
                stations.create(request.name(), request.consoleType()).getId()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Edit a station (Admin)",
            description = "409 DUPLICATE_NAME on a taken name; 409 STATION_IN_USE when the console "
                    + "type or the maintenance flag would move under a live session.")
    public StationView update(@PathVariable Long id, @Valid @RequestBody UpdateStationRequest request) {
        stations.update(id, request.name(), request.consoleType(), request.status());
        return StationView.of(stations.summary(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.ADMIN)
    @Operation(summary = "Remove a station (Admin)",
            description = "409 STATION_IN_USE while a session is live on it or any session history "
                    + "still points at it — put it under maintenance instead.")
    public void delete(@PathVariable Long id) {
        stations.delete(id);
    }
}
