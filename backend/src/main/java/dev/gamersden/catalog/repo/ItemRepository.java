package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.Item;
import dev.gamersden.catalog.domain.ItemCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * Active items at or below their reorder point, deepest shortfall first — S2's stock
     * watchlist. Sorted by how far under they are rather than by raw stock, so "0 of 12 left"
     * outranks "2 of 2 left".
     */
    @Query("""
            select item from Item item
            where item.active = true and item.stock <= item.reorderAt
            order by (item.stock - item.reorderAt) asc, item.stock asc, item.name asc
            """)
    List<Item> watchlist(Pageable page);
}
