package dev.gamersden.booking.repo;

import dev.gamersden.booking.domain.BookingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/** {@code booking_settings} — a single row keyed by {@code TRUE}, seeded by V003. */
public interface BookingSettingsRepository extends JpaRepository<BookingSettings, Boolean> {
}
