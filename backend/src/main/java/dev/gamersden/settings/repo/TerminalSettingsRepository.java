package dev.gamersden.settings.repo;

import dev.gamersden.settings.domain.TerminalSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerminalSettingsRepository extends JpaRepository<TerminalSettings, String> {
}
