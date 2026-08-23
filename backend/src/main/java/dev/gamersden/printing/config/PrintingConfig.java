package dev.gamersden.printing.config;

import dev.gamersden.common.config.GamersDenProperties;
import dev.gamersden.printing.domain.FakePrinterPortProvider;
import dev.gamersden.printing.domain.PlaceholderReceiptRenderer;
import dev.gamersden.printing.domain.PrinterPortProvider;
import dev.gamersden.printing.domain.ReceiptRenderer;
import dev.gamersden.printing.domain.Usb4JavaPrinterPortProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the {@code printing} package — two decisions, both of them seams.
 *
 * <p><strong>Which template set renders a job's bytes.</strong> The placeholder is registered only
 * while nothing else claims {@link ReceiptRenderer}, which is what TASKLIST B10 asks for —
 * "render stubbed until B17, behind the same interface". B17 contributes the escpos-coffee
 * renderer and this bean disappears; no caller, table or response shape moves with it.
 *
 * <p><strong>Which transport the queue prints through.</strong> {@code gamersden.printing.enabled}
 * is true on the {@code venue} profile alone (ARCHITECTURE.md §6): the cafe PC owns the USB
 * printer, the cloud mirror never prints, and CI and dev run the fake port
 * (docs/backend-architecture.md §10). Making it a bean condition rather than an {@code if} inside
 * the transport means the usb4java native library is never even loaded off the venue box.
 */
@Configuration
public class PrintingConfig {

    @Bean
    @ConditionalOnMissingBean(ReceiptRenderer.class)
    public ReceiptRenderer placeholderReceiptRenderer() {
        return new PlaceholderReceiptRenderer();
    }

    /** The cafe PC's real printer. */
    @Bean
    @ConditionalOnProperty(name = "gamersden.printing.enabled", havingValue = "true")
    public PrinterPortProvider usbPrinterPortProvider(GamersDenProperties properties) {
        return new Usb4JavaPrinterPortProvider(properties.printing().usbTimeout());
    }

    /**
     * Everywhere else. Exposed as its own type as well as as the interface, so a test can reach
     * {@link FakePrinterPortProvider#port()} and tell the printer to be out of paper without
     * casting the interface it was injected as.
     */
    @Bean
    @ConditionalOnMissingBean(PrinterPortProvider.class)
    public FakePrinterPortProvider fakePrinterPortProvider() {
        return new FakePrinterPortProvider();
    }
}
