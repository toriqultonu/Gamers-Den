package dev.gamersden.settings.repo;

import dev.gamersden.settings.domain.TerminalSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TerminalSettingsRepository extends JpaRepository<TerminalSettings, String> {

    /**
     * The serve path's single lookup: {@code login_bg_image_id} is UNIQUE across terminals (V005),
     * so an id identifies both the picture and the row it belongs to.
     */
    Optional<TerminalSettings> findByLoginBgImageId(String loginBgImageId);
}
