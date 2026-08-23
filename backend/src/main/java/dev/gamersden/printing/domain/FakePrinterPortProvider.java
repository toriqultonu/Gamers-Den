package dev.gamersden.printing.domain;

import java.util.List;

/**
 * The single fake device every profile but {@code venue} discovers. One printer, because that is
 * what the venue has: the multi-device machinery in {@code PrinterDirectory} and the worker exists
 * for the day a second counter is added, not because CI needs it.
 */
public class FakePrinterPortProvider implements PrinterPortProvider {

    public static final String DEVICE_ID = "fake-thermal-80";

    private final FakePrinterPort port = new FakePrinterPort(DEVICE_ID, "Fake thermal 80mm");

    @Override
    public List<PrinterPort> discover() {
        return List.of(port);
    }

    /** The one device, typed — how a test reaches the hooks without casting. */
    public FakePrinterPort port() {
        return port;
    }
}
