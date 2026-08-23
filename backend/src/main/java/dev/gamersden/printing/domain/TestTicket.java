package dev.gamersden.printing.domain;

import dev.gamersden.common.security.StaffPrincipal;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * The page {@code POST /printers/{printerId}/test} prints (TASKLIST B18, "test ticket").
 *
 * <p>Not one of P1–P7, so it does not live behind {@link ReceiptRenderer}: those are the venue's
 * documented artifacts (design.md §5) and B17 owns their bytes. This is a diagnostic — plain text
 * at the same 48 columns, which every ESC/POS device prints verbatim — and it answers the only
 * question it is asked: is this printer wired up, and which one is it?
 *
 * <p>It goes through the queue like any other job rather than writing to the port directly,
 * because a test that skipped the claim, the attempts and the status write would prove the printer
 * works while proving nothing about the path a receipt takes.
 */
final class TestTicket {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private TestTicket() {
    }

    static RenderedDocument render(PrinterDirectory.Printer printer, String deviceId,
                                   StaffPrincipal staff, OffsetDateTime at) {
        StringJoiner paper = new StringJoiner("\n");
        paper.add(centred("GAMER'S DEN"));
        paper.add(centred("PRINTER TEST"));
        paper.add(rule());
        paper.add(meta("PRINTER", printer.name()));
        paper.add(meta("DEVICE", printer.id()));
        paper.add(meta("QUEUE", deviceId));
        paper.add(meta("STATUS", printer.status().name()));
        paper.add(meta("AT", STAMP.format(at)));
        paper.add(meta("BY", "#" + staff.id()));
        paper.add(rule());
        // A full-width ruler: if the paper is 58 mm rather than 80 mm, or the font is not A, this
        // is the line that wraps and says so at a glance.
        paper.add("0123456789".repeat(RenderedDocument.COLUMNS / 10)
                + "01234567".substring(0, RenderedDocument.COLUMNS % 10));
        paper.add(rule());
        paper.add(centred("If you can read this line in full,"));
        paper.add(centred("the printer is ready."));
        return RenderedDocument.plainText(paper.toString());
    }

    private static String meta(String label, String value) {
        return "%-10s%s".formatted(label, value);
    }

    private static String centred(String text) {
        int pad = Math.max(0, (RenderedDocument.COLUMNS - text.length()) / 2);
        return " ".repeat(pad) + text;
    }

    private static String rule() {
        return "-".repeat(RenderedDocument.COLUMNS);
    }
}
