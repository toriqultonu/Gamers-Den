package dev.gamersden.printing.domain;

import dev.gamersden.common.config.GamersDenProperties;
import dev.gamersden.common.events.LiveEvents;
import dev.gamersden.common.error.ErrorCode;
import dev.gamersden.common.error.NotFoundException;
import dev.gamersden.common.error.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which printers exist and which one the venue prints to — what backs {@code GET /printers} and
 * {@code PUT /printers/default} (api-contract.md, "Print jobs").
 *
 * <p><strong>Why the choice is not in the database.</strong> There is no {@code printers} table in
 * any of the schema documents, and the printer model itself is an OPEN FLAG (ARCHITECTURE.md §8) —
 * inventing a table to persist a device id for hardware nobody has bought yet would be exactly the
 * guessing GLOBAL RULE 9 forbids. So the venue declares its printer in configuration
 * ({@code gamersden.printing.default-device}), the running process remembers an operator's
 * override, and a restart falls back to the declared one. One venue, one USB printer, one line of
 * YAML. When the model is confirmed and a second counter appears, this is the one class that has
 * to learn a table.
 *
 * <p><strong>Device id versus printer id.</strong> {@code print_jobs.device_id} is the terminal
 * that created the job — that is what {@code billing}, {@code shift}, {@code booking} and
 * {@code queue} have been passing since B10, and it is what the queue worker partitions on so two
 * counters never interleave their tickets. A printer id is a physical device. Today every
 * terminal's jobs resolve to the venue's default printer, which is why {@link #portFor(String)}
 * takes the job's device id and answers with the default: the mapping exists as a seam, not yet as
 * a configuration surface.
 */
@Service
public class PrinterDirectory {

    private static final Logger log = LoggerFactory.getLogger(PrinterDirectory.class);

    private final PrinterPortProvider provider;
    private final LiveEvents live;
    private final String configuredDefault;

    /** The operator's override for this run, if {@code PUT /printers/default} has been called. */
    private volatile String chosenDefault;

    public PrinterDirectory(PrinterPortProvider provider, GamersDenProperties properties,
                            LiveEvents live) {
        this.provider = provider;
        this.live = live;
        String declared = properties.printing().defaultDevice();
        this.configuredDefault = declared == null || declared.isBlank() ? null : declared.trim();
    }

    /**
     * Every attached printer with a live status poll, default first (api-contract.md: "live
     * status: ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN").
     *
     * <p>The status really is polled per call rather than remembered. S11 shows this to decide
     * whether to press Print, and a cached "ONLINE" from before someone opened the lid would be
     * worse than no status at all.
     */
    public List<Printer> list() {
        List<PrinterPort> ports = provider.discover();
        Optional<String> defaultId = defaultPrinterId(ports);
        Map<String, Printer> byId = new LinkedHashMap<>();
        for (PrinterPort port : ports) {
            byId.put(port.id(), new Printer(port.id(), port.name(), port.status(),
                    defaultId.map(port.id()::equals).orElse(false)));
        }
        return byId.values().stream()
                .sorted((a, b) -> Boolean.compare(b.isDefault(), a.isDefault()))
                .toList();
    }

    /**
     * The port a job queued by {@code deviceId} prints on.
     *
     * @throws ServiceUnavailableException 503 {@code PRINTER_UNAVAILABLE} when the venue has no
     *                                     printer at all — the case where there is nothing to fail
     *                                     a job against because there is nothing to fail
     */
    public PrinterPort portFor(String deviceId) {
        return findPortFor(deviceId).orElseThrow(() -> new ServiceUnavailableException(
                ErrorCode.PRINTER_UNAVAILABLE,
                "No printer is attached to this terminal (%s)".formatted(deviceId)));
    }

    /** The same lookup without the throw — the worker needs to fail the job, not the request. */
    public Optional<PrinterPort> findPortFor(String deviceId) {
        List<PrinterPort> ports = provider.discover();
        if (ports.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> preferred = defaultPrinterId(ports);
        return ports.stream()
                .filter(port -> preferred.map(port.id()::equals).orElse(false))
                .findFirst()
                .or(() -> ports.stream().findFirst());
    }

    /**
     * One attached printer by id, with a live status poll — 404 when nothing answers to it.
     * What {@code POST /printers/{printerId}/test} resolves before queueing its page.
     */
    public Printer require(String printerId) {
        PrinterPort port = port(printerId);
        return new Printer(port.id(), port.name(), port.status(),
                defaultPrinterId(provider.discover()).map(port.id()::equals).orElse(false));
    }

    /**
     * {@code PUT /printers/default}. Rejects an id nothing answers to: choosing a printer that is
     * not on the bus would silently send every subsequent ticket to whatever happened to be first.
     */
    public Printer chooseDefault(String printerId) {
        PrinterPort port = port(printerId);
        this.chosenDefault = printerId;
        log.info("default printer set to {} ({})", port.id(), port.name());
        // Which device the venue prints on is part of what GET /printers answers, so every screen
        // showing the printer banner is told (§4.5). No transaction is involved — this is a
        // process-level choice — which is why the emitter runs with fallbackExecution.
        live.printerStatusChanged();
        return new Printer(port.id(), port.name(), port.status(), true);
    }

    private PrinterPort port(String printerId) {
        return provider.discover().stream()
                .filter(candidate -> candidate.id().equals(printerId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Printer", printerId));
    }

    /** The override if one was made this run, else the configured device, else the first found. */
    private Optional<String> defaultPrinterId(List<PrinterPort> discovered) {
        if (chosenDefault != null) {
            return Optional.of(chosenDefault);
        }
        if (configuredDefault != null) {
            return Optional.of(configuredDefault);
        }
        return discovered.stream().findFirst().map(PrinterPort::id);
    }

    /** One row of {@code GET /printers}. */
    public record Printer(String id, String name, PrinterStatus status, boolean isDefault) {
    }
}
