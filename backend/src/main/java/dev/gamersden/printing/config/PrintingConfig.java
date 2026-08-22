package dev.gamersden.printing.config;

import dev.gamersden.printing.domain.PlaceholderReceiptRenderer;
import dev.gamersden.printing.domain.ReceiptRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the {@code printing} package. Today that is one decision: which template set renders
 * a job's bytes.
 *
 * <p>The placeholder is registered only while nothing else claims {@link ReceiptRenderer}, which
 * is the seam TASKLIST B10 asks for — "render stubbed until B17, behind the same interface". B17
 * contributes the escpos-coffee renderer and this bean disappears; no caller, table or response
 * shape moves with it.
 */
@Configuration
public class PrintingConfig {

    @Bean
    @ConditionalOnMissingBean(ReceiptRenderer.class)
    public ReceiptRenderer placeholderReceiptRenderer() {
        return new PlaceholderReceiptRenderer();
    }
}
