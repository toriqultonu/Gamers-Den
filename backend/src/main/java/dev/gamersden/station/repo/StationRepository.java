package dev.gamersden.station.repo;

import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.Station;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationRepository extends JpaRepository<Station, Long> {

    Optional<Station> findByName(String name);

    boolean existsByName(String name);

    List<Station> findByConsoleType(ConsoleType consoleType);
}
