package dev.gamersden.auth.repo;

import dev.gamersden.auth.domain.Staff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    Optional<Staff> findByName(String name);

    boolean existsByName(String name);
}
