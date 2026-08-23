package dev.gamersden.printing.domain;

import java.util.List;

/**
 * Where the {@link PrinterPort}s come from. Exactly one implementation is wired per profile (see
 * {@code printing/config/PrintingConfig}): usb4java on the venue box, the fake everywhere else —
 * which is how CI, the cloud mirror and a developer laptop run the whole print path with no
 * hardware attached (docs/backend-architecture.md §10).
 *
 * <p>Discovery is re-run on demand rather than cached at startup, because a USB printer that was
 * unplugged at boot and plugged in afterwards has to show up on {@code GET /printers} without a
 * restart.
 */
public interface PrinterPortProvider {

    /** Every printer currently attached, in a stable order. */
    List<PrinterPort> discover();
}
