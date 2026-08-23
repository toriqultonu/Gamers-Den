package dev.gamersden.printing.domain;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.ConfigDescriptor;
import org.usb4java.Device;
import org.usb4java.DeviceDescriptor;
import org.usb4java.DeviceList;
import org.usb4java.EndpointDescriptor;
import org.usb4java.Interface;
import org.usb4java.InterfaceDescriptor;
import org.usb4java.LibUsb;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the USB printers attached to the cafe PC (ARCHITECTURE.md §2, §6). Instantiated only
 * when {@code gamersden.printing.enabled} is true, so the native library is never loaded in CI, on
 * the cloud mirror, or on a developer laptop — the fake provider stands in there.
 *
 * <p>Selection is by USB class rather than by vendor id: the printer model is an OPEN FLAG (§8),
 * so anything that presents interface class 7 (printer) with a bulk-out endpoint is treated as one
 * rather than hard-coding a device that has not been bought yet. The device id is
 * {@code usb:VVVV:PPPP:BB.DD} — vendor, product, bus and address — which stays stable across
 * reconnects of the same physical port and distinguishes two identical printers.
 */
public class Usb4JavaPrinterPortProvider implements PrinterPortProvider {

    private static final Logger log = LoggerFactory.getLogger(Usb4JavaPrinterPortProvider.class);

    /** USB device/interface class 7 — printers. */
    private static final byte PRINTER_CLASS = 7;

    private final Duration timeout;
    private boolean initialised;

    public Usb4JavaPrinterPortProvider(Duration timeout) {
        this.timeout = timeout;
        int result = LibUsb.init(null);
        if (result != LibUsb.SUCCESS) {
            // Not fatal: the venue must still boot and take money with a dead printer, and every
            // job it queues stays QUEUED until someone fixes the box (invariant §5.3 — the money
            // write never depends on paper).
            log.error("libusb init failed ({}); no USB printer will be discovered",
                    LibUsb.errorName(result));
            return;
        }
        this.initialised = true;
    }

    @Override
    public List<PrinterPort> discover() {
        if (!initialised) {
            return List.of();
        }
        DeviceList devices = new DeviceList();
        int result = LibUsb.getDeviceList(null, devices);
        if (result < 0) {
            log.warn("cannot list USB devices: {}", LibUsb.errorName(result));
            return List.of();
        }
        List<PrinterPort> ports = new ArrayList<>();
        try {
            for (Device device : devices) {
                describe(device).ifPresent(ports::add);
            }
        } finally {
            // The ports keep references to their devices, so the list is unreferenced without
            // unreferencing the devices themselves.
            LibUsb.freeDeviceList(devices, false);
        }
        return List.copyOf(ports);
    }

    @PreDestroy
    public void shutdown() {
        if (initialised) {
            LibUsb.exit(null);
            initialised = false;
        }
    }

    /** A device becomes a port only if it really is a printer with both endpoints we need. */
    private java.util.Optional<PrinterPort> describe(Device device) {
        DeviceDescriptor descriptor = new DeviceDescriptor();
        if (LibUsb.getDeviceDescriptor(device, descriptor) != LibUsb.SUCCESS) {
            return java.util.Optional.empty();
        }
        ConfigDescriptor config = new ConfigDescriptor();
        if (LibUsb.getActiveConfigDescriptor(device, config) != LibUsb.SUCCESS) {
            return java.util.Optional.empty();
        }
        try {
            for (Interface iface : config.iface()) {
                for (InterfaceDescriptor setting : iface.altsetting()) {
                    if (setting.bInterfaceClass() != PRINTER_CLASS) {
                        continue;
                    }
                    Byte out = endpoint(setting, LibUsb.ENDPOINT_OUT);
                    Byte in = endpoint(setting, LibUsb.ENDPOINT_IN);
                    if (out == null || in == null) {
                        // No bulk-in endpoint means no DLE EOT answer, and a printer we cannot
                        // poll is a printer whose failures we could only report as "transport
                        // error" — refuse it rather than lie about paper and covers.
                        continue;
                    }
                    String id = "usb:%04x:%04x:%02x.%02x".formatted(
                            descriptor.idVendor() & 0xFFFF, descriptor.idProduct() & 0xFFFF,
                            LibUsb.getBusNumber(device), LibUsb.getDeviceAddress(device));
                    return java.util.Optional.of(new Usb4JavaPrinterPort(id, "Thermal printer " + id,
                            device, setting.bInterfaceNumber(), out, in, timeout));
                }
            }
            return java.util.Optional.empty();
        } finally {
            LibUsb.freeConfigDescriptor(config);
        }
    }

    private static Byte endpoint(InterfaceDescriptor setting, byte direction) {
        for (EndpointDescriptor endpoint : setting.endpoint()) {
            boolean bulk = (endpoint.bmAttributes() & LibUsb.TRANSFER_TYPE_MASK)
                    == LibUsb.TRANSFER_TYPE_BULK;
            if (bulk && (endpoint.bEndpointAddress() & LibUsb.ENDPOINT_DIR_MASK) == direction) {
                return endpoint.bEndpointAddress();
            }
        }
        return null;
    }
}
