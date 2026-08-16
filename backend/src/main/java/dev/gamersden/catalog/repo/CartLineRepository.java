package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.CartLine;
import dev.gamersden.catalog.domain.CartLineId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartLineRepository extends JpaRepository<CartLine, CartLineId> {

    List<CartLine> findByIdCartId(Long cartId);
}
