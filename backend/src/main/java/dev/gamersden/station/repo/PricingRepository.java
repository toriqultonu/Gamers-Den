package dev.gamersden.station.repo;

import dev.gamersden.station.domain.ConsoleType;
import dev.gamersden.station.domain.Pricing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingRepository extends JpaRepository<Pricing, ConsoleType> {
}
