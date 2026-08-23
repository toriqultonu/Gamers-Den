package dev.gamersden.printing.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.Device;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.time.Duration;

/**
 * The real transport: raw bulk USB, never the OS spooler (ARCHITECTURE.md §2). Wired only on the
 * {@code venue} profile — the cafe PC is the one machine that owns a printer (§6).
 *
 * <p>Two things happen here that cannot happen anywhere else in the system:
 *
 * <ul>
 *   <li><strong>DLE EOT status polling.</strong> The three unhappy answers on S11 — offline, out
 *       of paper, cover open — are real-time transmission-status queries the printer answers even
 *       while it is busy. That is why the queue can say <em>which</em> thing is wrong instead of
 *       "printing failed" (docs/backend-architecture.md §5).</li>
 *   <li><strong>The mid-print distinction.</strong> A bulk transfer that reports fewer bytes
 *       written than it was handed means part of the ticket is on paper and the rest is not, which
 *       is {@link PrintFailure#MID_PRINT} and nothing else — the case §5 says must reprint the
 *       full ticket on retry rather than resume.</li>
 * </ul>
 *
 * <p>The handle is opened per ticket and closed after it. A thermal printer at a counter prints a
 * few times a minute; holding a claimed USB interface open for the life of the JVM would only make
 * the device impossible to share with a diagnostics tool and awkward to recover by unplugging.
 *
 * <p>OPEN FLAG (ARCHITECTURE.md §8): the printer model is unconfirmed. The DLE EOT bit masks below
 * are the ESC/POS standard ones and hold for every Epson-compatible 80 mm device; they are the
 * documented default, not a guess at a specific model.
 */
public class Usb4JavaPrinterPort implements PrinterPort {

    private static final Logger log = LoggerFactory.getLogger(Usb4JavaPrinterPort.class);

    /** ESC/POS real-time status: {@code DLE EOT n}. */
    private static final byte DLE = 0x10;
    private static final byte EOT = 0x04;

    /** {@code n=1} printer status — bit 3 set means offline. */
    private static final byte STATUS_PRINTER = 1;
    private static final int PRINTER_OFFLINE = 0x08;

    /** {@code n=2} offline cause — bit 2 set means the cover is open. */
    private static final byte STATUS_OFFLINE_CAUSE = 2;
    private static final int COVER_OPEN = 0x04;

    /** {@code n=4} paper sensor — bits 5 and 6 set means paper end. */
    private static final byte STATUS_PAPER = 4;
    private static final int PAPER_END = 0x60;

    private final String id;
    private final String name;
    private final Device device;
    private final byte outEndpoint;
    private final byte inEndpoint;
    private final int interfaceNumber;
    private final long timeoutMillis;

    public Usb4JavaPrinterPort(String id, String name, Device device, int interfaceNumber,
                               byte outEndpoint, byte inEndpoint, Duration timeout) {
        this.id = id;
        this.name = name;
        this.device = device;
        this.interfaceNumber = interfaceNumber;
        this.outEndpoint = outEndpoint;
        this.inEndpoint = inEndpoint;
        this.timeoutMillis = timeout.toMillis();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * One DLE EOT round trip per question, in the order that makes the answer actionable: a
     * printer with its cover open is also "offline", so the specific causes are asked first and
     * the generic bit is only believed when neither of them explains it.
     *
     * <p>A device that cannot be opened at all is {@link PrinterStatus#OFFLINE} — unplugged is
     * indistinguishable from powered off from here, and both mean the same thing to the operator.
     */
    @Override
    public synchronized PrinterStatus status() {
        DeviceHandle handle = null;
        try {
            handle = claim();
            if (bitsSet(handle, STATUS_PAPER, PAPER_END)) {
                return PrinterStatus.OUT_OF_PAPER;
            }
            if (bitsSet(handle, STATUS_OFFLINE_CAUSE, COVER_OPEN)) {
                return PrinterStatus.COVER_OPEN;
            }
            if (bitsSet(handle, STATUS_PRINTER, PRINTER_OFFLINE)) {
                return PrinterStatus.OFFLINE;
            }
            return PrinterStatus.ONLINE;
        } catch (PrinterTransportException | LibUsbException e) {
            log.warn("printer {} did not answer a status poll: {}", id, e.getMessage());
            return PrinterStatus.OFFLINE;
        } finally {
            release(handle);
        }
    }

    @Override
    public synchronized void write(byte[] bytes) {
        DeviceHandle handle = null;
        try {
            handle = claim();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
            buffer.put(bytes);
            buffer.rewind();
            IntBuffer transferred = IntBuffer.allocate(1);
            int result = LibUsb.bulkTransfer(handle, outEndpoint, buffer, transferred, timeoutMillis);
            if (result != LibUsb.SUCCESS) {
                throw new PrinterTransportException(
                        transferred.get(0) > 0 ? PrintFailure.MID_PRINT : PrintFailure.OFFLINE,
                        "bulk transfer to %s failed: %s".formatted(id, LibUsb.errorName(result)));
            }
            if (transferred.get(0) != bytes.length) {
                throw new PrinterTransportException(PrintFailure.MID_PRINT,
                        "printer %s took %d of %d bytes"
                                .formatted(id, transferred.get(0), bytes.length));
            }
        } catch (LibUsbException e) {
            throw new PrinterTransportException(PrintFailure.TRANSPORT,
                    "printer %s: %s".formatted(id, e.getMessage()), e);
        } finally {
            release(handle);
        }
    }

    /** {@code DLE EOT n} out, one status byte back. */
    private boolean bitsSet(DeviceHandle handle, byte question, int mask) {
        ByteBuffer out = ByteBuffer.allocateDirect(3);
        out.put(DLE).put(EOT).put(question).rewind();
        IntBuffer sent = IntBuffer.allocate(1);
        int written = LibUsb.bulkTransfer(handle, outEndpoint, out, sent, timeoutMillis);
        if (written != LibUsb.SUCCESS) {
            throw new PrinterTransportException(PrintFailure.OFFLINE,
                    "status query %d to %s failed: %s"
                            .formatted(question, id, LibUsb.errorName(written)));
        }
        ByteBuffer in = ByteBuffer.allocateDirect(1);
        IntBuffer read = IntBuffer.allocate(1);
        int answered = LibUsb.bulkTransfer(handle, inEndpoint, in, read, timeoutMillis);
        if (answered != LibUsb.SUCCESS || read.get(0) < 1) {
            throw new PrinterTransportException(PrintFailure.OFFLINE,
                    "printer %s did not answer status query %d".formatted(id, question));
        }
        return (in.get(0) & mask) == mask;
    }

    private DeviceHandle claim() {
        DeviceHandle handle = new DeviceHandle();
        int opened = LibUsb.open(device, handle);
        if (opened != LibUsb.SUCCESS) {
            throw new PrinterTransportException(PrintFailure.OFFLINE,
                    "cannot open printer %s: %s".formatted(id, LibUsb.errorName(opened)));
        }
        // Linux binds usblp to printer-class devices; without this the interface claim below fails
        // on exactly the kind of box the venue runs. Unsupported elsewhere, and harmless there.
        LibUsb.setAutoDetachKernelDriver(handle, true);
        int claimed = LibUsb.claimInterface(handle, interfaceNumber);
        if (claimed != LibUsb.SUCCESS) {
            LibUsb.close(handle);
            throw new PrinterTransportException(PrintFailure.OFFLINE,
                    "cannot claim printer %s: %s".formatted(id, LibUsb.errorName(claimed)));
        }
        return handle;
    }

    private void release(DeviceHandle handle) {
        if (handle == null) {
            return;
        }
        LibUsb.releaseInterface(handle, interfaceNumber);
        LibUsb.close(handle);
    }
}
