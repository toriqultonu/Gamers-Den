package dev.gamersden.printing.config;

import dev.gamersden.printing.domain.EscPosReceiptRenderer;
import dev.gamersden.printing.domain.ReceiptRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the {@code printing} package. Today that is one decision: which template set renders
 * a job's bytes.
 *
 * <p>B17 retired the plain-text placeholder B10 stood up and put the real P1–P7 ESC/POS templates
 * behind the same {@link ReceiptRenderer} seam — so settle, booking create and booking check-in
 * store real bytes now without a caller having changed. {@code ConditionalOnMissingBean} stays
 * because the seam is still worth having: a test or a future paper format can supply its own
 * renderer and everything downstream keeps working.
 */
@Configuration
public class PrintingConfig {

    @Bean
    @ConditionalOnMissingBean(ReceiptRenderer.class)
    public ReceiptRenderer escPosReceiptRenderer(ReceiptProperties properties) {
        return new EscPosReceiptRenderer(properties.paperWidth(), properties.venueName(),
                properties.address(), properties.phone());
    }
}
