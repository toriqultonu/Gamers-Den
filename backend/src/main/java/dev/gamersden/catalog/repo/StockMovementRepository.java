package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByItemIdOrderByIdDesc(Long itemId);
}
