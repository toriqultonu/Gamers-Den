package dev.gamersden.alert.repo;

import dev.gamersden.alert.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByReadFalseOrderByIdDesc();
}
