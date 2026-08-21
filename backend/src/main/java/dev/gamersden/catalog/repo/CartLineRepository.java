package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.CartLine;
import dev.gamersden.catalog.domain.CartLineId;
import dev.gamersden.catalog.domain.ItemHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CartLineRepository extends JpaRepository<CartLine, CartLineId> {

    List<CartLine> findByIdCartId(Long cartId);

    List<CartLine> findByIdCartIdOrderByIdItemIdAsc(Long cartId);

    boolean existsByIdItemId(Long itemId);

    /**
     * How much of an item is already spoken for by <em>other</em> open carts. Stock itself is not
     * decremented until settle (§1 money path), so without this an item's last unit could sit on
     * two carts at once and both would settle.
     */
    @Query("""
            select coalesce(sum(line.qty), 0) from CartLine line
            where line.id.itemId = :itemId
              and line.id.cartId <> :exceptCartId
              and line.id.cartId in (select cart.id from Cart cart where cart.settled = false)
            """)
    long heldElsewhere(@Param("itemId") Long itemId, @Param("exceptCartId") Long exceptCartId);

    /** The same figure across every open cart — what one item's menu card counts down from. */
    @Query("""
            select coalesce(sum(line.qty), 0) from CartLine line
            where line.id.itemId = :itemId
              and line.id.cartId in (select cart.id from Cart cart where cart.settled = false)
            """)
    long heldOnOpenCarts(@Param("itemId") Long itemId);

    /** The same figure for every item at once — one query behind the menu read. */
    @Query("""
            select new dev.gamersden.catalog.domain.ItemHold(line.id.itemId, sum(line.qty))
            from CartLine line
            where line.id.cartId in (select cart.id from Cart cart where cart.settled = false)
            group by line.id.itemId
            """)
    List<ItemHold> heldByItem();
}
