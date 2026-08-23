package dev.gamersden.alert.repo;

import dev.gamersden.alert.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByReadFalseOrderByIdDesc();

    /** The feed: newest first, because that is the one an operator has not seen yet. */
    List<Alert> findByOrderByIdDesc();

    long countByReadFalse();

    /**
     * "Mark all read" as one statement rather than a row at a time: the rail's button is pressed
     * against whatever is on screen, and loading a day of alerts to flip a boolean on each would
     * be work for nobody's benefit.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Alert a set a.read = true where a.read = false")
    int markAllRead();
}
