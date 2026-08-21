package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.Item;
import dev.gamersden.catalog.domain.ItemCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemRepository extends JpaRepository<Item, Long> {

    Optional<Item> findByName(String name);

    boolean existsByName(String name);

    List<Item> findByActiveTrueOrderByNameAsc();

    /** The menu read order (design.md S5/S7): grouped by category, alphabetical inside it. */
    List<Item> findAllByOrderByCategoryAscNameAsc();

    List<Item> findByCategoryOrderByNameAsc(ItemCategory category);

    List<Item> findByActiveOrderByCategoryAscNameAsc(boolean active);

    List<Item> findByActiveAndCategoryOrderByNameAsc(boolean active, ItemCategory category);
}
