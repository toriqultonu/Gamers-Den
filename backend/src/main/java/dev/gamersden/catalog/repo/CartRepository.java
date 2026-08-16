package dev.gamersden.catalog.repo;

import dev.gamersden.catalog.domain.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findBySessionId(Long sessionId);
}
