package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.CartLine;
import dev.gamersden.catalog.domain.CartLineId;
import dev.gamersden.catalog.domain.ItemHold;
import dev.gamersden.catalog.domain.ItemSold;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * What those carts sold, best by revenue first — S9's top-seller table.
     *
     * <p>Which carts count is {@code billing}'s call, not this package's: it hands over the ones a
     * sale that stuck settled inside the window ({@code RevenueLookup.settledCartIds}), and all
     * that happens here is pricing their lines out of the columns {@code catalog} owns.
     */
    @Query("""
            select new dev.gamersden.catalog.domain.ItemSold(
                       line.id.itemId, item.name, item.category,
                       sum(line.qty), sum(line.qty * line.unitPrice))
            from CartLine line join Item item on item.id = line.id.itemId
            where line.id.cartId in :cartIds
            group by line.id.itemId, item.name, item.category
            order by sum(line.qty * line.unitPrice) desc, sum(line.qty) desc, item.name asc
            """)
    List<ItemSold> soldOn(@Param("cartIds") Collection<Long> cartIds, Pageable page);
}
