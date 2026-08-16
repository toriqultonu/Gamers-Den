package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByName(String name);

    boolean existsByName(String name);

    List<Item> findByActiveTrueOrderByNameAsc();
}
