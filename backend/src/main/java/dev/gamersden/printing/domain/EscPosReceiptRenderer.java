package dev.gamersden.printing.domain;

import dev.gamersden.common.config.VenueTime;
import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * P1–P7, in ESC/POS (design.md §5). Replaces the B10/B11/B15 placeholder behind the same
 * {@link ReceiptRenderer} seam, so settle, booking create and booking check-in now store real
 * bytes without a caller, a table or a response shape moving.
 *
 * <p>Nothing here reaches for data: what a template prints is what its {@code common.spi} record
 * carries, because the render happens inside the money transaction that created the job
 * (invariant §5.3) and a template that went looking for a row would be a query the settle did not
 * mean to make. Where design.md asks for a figure the record does not hold, it is derived from
 * what the record does hold — P7's cancellation window is the gap between the booking's own
 * {@code startAt} and its {@code cancellableUntil} snapshot, so the customer is quoted the
 * deadline they were actually sold rather than today's setting.
 *
 * <p>Money is printed as {@code Tk}, not the taka sign the screens use. No ESC/POS code page
 * carries U+09F3, so a receipt asking for it prints a substitution character; {@code Tk} is what
 * the paper can actually say. This is deliberately the one place the paper's vocabulary differs
 * from the UI's — see also the open flag on the printer model (ARCHITECTURE.md §8).
 */
public class EscPosReceiptRenderer implements ReceiptRenderer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** What a thermal printer can print for integer BDT. */
    private static final String CURRENCY = "Tk";

    /** How much of a provider reference ever reaches paper or a log line (invariant §5.12). */
    private static final int REF_TAIL = 6;

    /** The heading {@code billing} gives a sale with no seat behind it (design.md §5 P1). */
    private static final String COUNTER_SALE = "Counter sale";

    private final PaperWidth paper;
    private final String venueName;
    private final String address;
    private final String phone;

    public EscPosReceiptRenderer(PaperWidth paper, String venueName, String address, String phone) {
        this.paper = paper;
        this.venueName = venueName;
        this.address = address;
        this.phone = phone;
    }

    // ---- P1 -----------------------------------------------------------------------------------

    /**
     * P1 — the sale ticket. Header, meta block, lines, double-height TOTAL, one row per tender,
     * loyalty line, then whatever stubs this sale earned, then the Code 128 of the transaction id.
     *
     * <p>The stubs ride on this job rather than jobs of their own (invariant §5.5): a customer who
     * paid once takes away one piece of paper, and the receipt and the ticket it paid for cannot
     * be separated by a printer failure that takes only one of them.
     */
    @Override
    public RenderedDocument renderSale(SaleReceiptPrinting.SaleReceipt receipt) {
        EscPosDocument doc = header();
        doc.meta("TXN", receipt.publicId());
        // design.md §5 labels this row STATION when a seat is being settled, TYPE when the sale is
        // nothing but counter business; billing already decided which by the heading it passed.
        doc.meta(COUNTER_SALE.equals(receipt.heading()) ? "TYPE" : "STATION", receipt.heading());
        doc.meta("AT", stamp(receipt.at()));
        doc.meta("CASHIER", "#" + receipt.operatorId());
        doc.rule();

        receipt.lines().forEach(line -> doc.row(lineLabel(line), money(line.amount())));
        if (receipt.pointsRedeemed() > 0) {
            doc.row("POINTS %dPTS".formatted(receipt.pointsRedeemed()),
                    money(-receipt.pointsRedeemed()));
        }
        doc.rule();
        doc.bigRow("TOTAL", money(receipt.total()));
        receipt.tenders().forEach(tender -> doc.row(tenderLabel(tender), money(tender.amount())));

        if (receipt.pointsEarned() > 0 || receipt.pointsBalance() != null) {
            doc.rule();
            doc.centred(receipt.pointsBalance() == null
                    ? "Points earned %d".formatted(receipt.pointsEarned())
                    : "Points earned %d - balance %d".formatted(receipt.pointsEarned(),
                            receipt.pointsBalance()));
        }

        receipt.entryStubs().forEach(stub -> entryStub(doc, stub));
        if (receipt.bookingStub() != null) {
            bookingStub(doc, receipt.bookingStub());
        }
        receipt.playTicketStubs().forEach(stub -> playTicketStub(doc, stub));

        doc.rule();
        doc.centred("See you again at the Den");
        doc.code128(receipt.publicId());
        return doc.cut();
    }

    // ---- P5 -----------------------------------------------------------------------------------

    /**
     * P5, appended to the sale receipt in the same job (docs/tournaments.md §7): inverted band,
     * the event, the player, a double-height {@code TOKEN #NN}, and the QR that is their bracket
     * pass.
     *
     * <p>The QR carries the opaque {@code qr_token} and nothing else — design.md §5 is explicit
     * that no PII goes into the symbol, which is also why this token is never abbreviated the way
     * a payment reference is: it <em>is</em> the ticket.
     */
    private void entryStub(EscPosDocument doc, SaleReceiptPrinting.EntryStub stub) {
        doc.rule();
        doc.band("TOURNAMENT ENTRY");
        doc.centred(stub.tournamentName());
        doc.centred("Player: " + stub.playerName());
        doc.emphasised("TOKEN #%02d".formatted(stub.seed()));
        doc.qr(stub.qrToken());
        doc.centred("Show this ticket at the bracket desk");
    }

    // ---- P7 -----------------------------------------------------------------------------------

    /**
     * P7, appended to the sale receipt in the same job (docs/bookings.md §2): the BOOKING band,
     * the console, the start time, the play time, the package fee, and the cancellation policy.
     *
     * <p>Both cancellation lines are printed on purpose. The policy line is the rule the customer
     * agreed to; the {@code CANCEL BY} row is that rule resolved against this booking's own
     * snapshot, and it is the figure they will come back and argue about.
     */
    private void bookingStub(EscPosDocument doc, SaleReceiptPrinting.BookingStub stub) {
        doc.rule();
        doc.band("BOOKING CONFIRMED");
        doc.meta("BOOKING", "#" + stub.bookingId());
        doc.meta("NAME", stub.playerName());
        if (stub.phone() != null && !stub.phone().isBlank()) {
            doc.meta("PHONE", stub.phone());
        }
        doc.meta("CONSOLE", "%s - %s".formatted(stub.stationName(), stub.consoleType()));
        doc.meta("STARTS", stamp(stub.startAt()));
        doc.row("PLAY %dx30M".formatted(stub.blocks()), money(stub.playAmount()));
        doc.row("PACKAGE FEE", money(stub.packageFee()));
        doc.meta("CANCEL BY", stamp(stub.cancellableUntil()));
        doc.centred("Full refund until %d h before start".formatted(cutoffHours(stub)));
        doc.centred("Check in at the counter on arrival");
    }

    /**
     * The cancellation window this booking was sold, read back off its own two timestamps rather
     * than off {@code booking_settings} — the setting can change, the booking's snapshot cannot
     * (invariant §5.11).
     */
    private static long cutoffHours(SaleReceiptPrinting.BookingStub stub) {
        return Duration.between(stub.cancellableUntil(), stub.startAt()).toHours();
    }

    // ---- P6 -----------------------------------------------------------------------------------

    /**
     * P6 riding on the sale that paid for it (docs/bookings.md §3) — a play ticket bought at the
     * counter is handed over with its receipt, so it is one piece of paper. Same body as the
     * standalone stub below, minus the header a check-in earns.
     */
    private void playTicketStub(EscPosDocument doc, SaleReceiptPrinting.PlayTicketStub stub) {
        doc.rule();
        doc.band("PLAY TICKET");
        doc.centred("Player: " + stub.playerName());
        doc.emphasised("TOKEN #%02d".formatted(stub.tokenNo()));
        doc.centred(prepaidLine(stub.consoleType(), stub.blocks()));
        doc.centred("Tokens reset daily");
        doc.code128(String.valueOf(stub.queueEntryId()));
    }

    /**
     * P6 standalone — a booking check-in issues a token but takes no money, so there is no sale
     * receipt for the stub to ride on (invariant §5.5). Only the heading tells the two apart:
     * "PLAY TICKET — PREBOOKED" says the time is already bought, which is what the floor staff
     * need to see before they seat someone who is not going to pay again.
     */
    @Override
    public RenderedDocument renderPlayTicket(PlayTicketPrinting.PlayTicket ticket) {
        EscPosDocument doc = header();
        doc.band(ticket.prebooked() ? "PLAY TICKET - PREBOOKED" : "PLAY TICKET");
        doc.centred("Player: " + ticket.playerName());
        doc.emphasised("TOKEN #%02d".formatted(ticket.tokenNo()));
        doc.centred(prepaidLine(ticket.consoleType(), ticket.blocks()));
        doc.rule();
        if (ticket.stationName() != null) {
            doc.meta("BOOKED", ticket.stationName());
        }
        if (ticket.startAt() != null) {
            doc.meta("SLOT", stamp(ticket.startAt()));
        }
        doc.meta("ISSUED", stamp(ticket.at()));
        doc.meta("DAY", ticket.tokenDate().toString());
        doc.rule();
        doc.centred("Tokens reset daily");
        doc.centred("Wait for your token to be called");
        // The barcode is the queue entry, not the token number: the number is reused tomorrow and
        // the entry id is what still resolves after a rollover (invariant §5.10).
        doc.code128(String.valueOf(ticket.queueEntryId()));
        return doc.cut();
    }

    /** design.md §5 P6: console type and prepaid length on one line, e.g. {@code PS5 - 2 H PREPAID}. */
    private static String prepaidLine(String consoleType, int blocks) {
        String length = blocks % 2 == 0
                ? "%d H".formatted(blocks / 2)
                : "%d.5 H".formatted(blocks / 2);
        return "%s - %s PREPAID".formatted(consoleType, length);
    }

    // ---- P2 / P3 ------------------------------------------------------------------------------

    /**
     * P2 / P3. One template, two headings: the X is the Z minus the drawer, the discrepancy and
     * the signature line, and it carries no barcode (design.md §5) — an interim read is not a
     * document anyone files, so there is nothing to scan it back up by.
     */
    @Override
    public RenderedDocument renderShiftReport(ShiftReportPrinting.ShiftReport report) {
        boolean interim = report.kind() == ShiftReportPrinting.Kind.X;
        EscPosDocument doc = header();
        doc.emphasised(interim ? "X REPORT - INTERIM" : "Z REPORT");
        doc.rule();
        doc.meta("SHIFT", "#" + report.shiftId());
        doc.meta("TERMINAL", report.terminal());
        doc.meta("OPERATOR", "#" + report.shiftStaffId());
        doc.meta("OPENED", stamp(report.openedAt()));
        doc.meta(interim ? "AS OF" : "CLOSED",
                stamp(report.closedAt() == null ? report.at() : report.closedAt()));
        doc.rule();
        doc.row("OPENING FLOAT", money(report.openingFloat()));
        doc.rule();
        doc.centred("TAKINGS BY METHOD (%s)".formatted(CURRENCY));
        takings(doc, report);
        doc.rule();
        doc.row("POINTS REDEEMED", money(-report.pointsRedeemed()));
        doc.row("POINTS EARNED", String.valueOf(report.pointsEarned()));
        doc.row("SALES", String.valueOf(report.saleCount()));
        doc.row("REFUNDS", String.valueOf(report.refundCount()));
        doc.rule();
        doc.centred("EXPENSES");
        report.expenses().forEach(expense -> doc.row(
                "%s (%s)".formatted(expense.description(), expense.category()),
                money(-expense.amount())));
        doc.row("EXPENSES TOTAL", money(-report.expensesTotal()));

        if (!interim) {
            doc.rule();
            doc.row("EXPECTED CASH", money(nullSafe(report.expectedCash())));
            doc.row("COUNTED CASH", money(nullSafe(report.countedCash())));
            doc.bigRow("DISCREPANCY", money(nullSafe(report.discrepancy())));
            if (report.handoverNote() != null && !report.handoverNote().isBlank()) {
                doc.rule();
                doc.meta("HANDOVER", report.handoverNote());
            }
            doc.rule();
            doc.signature("SIGNATURE");
            doc.code128("SHIFT-" + report.shiftId());
        }
        return doc.cut();
    }

    /**
     * The takings matrix — method against category, with the pre-booking and tournament columns
     * that reconciliation depends on (invariant §5.7).
     *
     * <p>On 80 mm paper it is a six-column grid. On the 58 mm switch there is no room for one, so
     * each method's categories stack under its total instead: the same figures, laid out for the
     * width that is actually there.
     */
    private void takings(EscPosDocument doc, ShiftReportPrinting.ShiftReport report) {
        if (paper.fitsTakingsGrid()) {
            doc.line(gridRow("METHOD", "GAMING", "F&B", "TOURN", "BOOK", "TOTAL"));
            report.byMethod().forEach(line -> doc.line(gridRow(line)));
            doc.rule();
            doc.line(gridRow(report.totals()));
            return;
        }
        report.byMethod().forEach(line -> stackedTakings(doc, line));
        doc.rule();
        stackedTakings(doc, report.totals());
    }

    private void stackedTakings(EscPosDocument doc, ShiftReportPrinting.MethodLine line) {
        doc.row(line.method(), money(line.total()));
        doc.row("  gaming", plain(line.gaming()));
        doc.row("  fnb", plain(line.fnb()));
        doc.row("  tournament", plain(line.tournament()));
        doc.row("  pre-booking", plain(line.booking()));
    }

    private static String gridRow(ShiftReportPrinting.MethodLine line) {
        return gridRow(line.method(), plain(line.gaming()), plain(line.fnb()),
                plain(line.tournament()), plain(line.booking()), plain(line.total()));
    }

    /** Eight characters of label plus five eight-character cells — exactly 48 columns. */
    private static String gridRow(String label, String gaming, String fnb, String tournament,
                                  String booking, String total) {
        return "%-8s%8s%8s%8s%8s%8s".formatted(label, gaming, fnb, tournament, booking, total);
    }

    // ---- P4 -----------------------------------------------------------------------------------

    /** P4 — what someone signs for the money they took out of the drawer (design.md §5). */
    @Override
    public RenderedDocument renderExpenseVoucher(ExpenseVoucherPrinting.ExpenseVoucher voucher) {
        EscPosDocument doc = header();
        doc.emphasised("EXPENSE VOUCHER");
        doc.rule();
        doc.meta("AT", stamp(voucher.at()));
        doc.meta("SHIFT", "#" + voucher.shiftId());
        doc.meta("CATEGORY", voucher.category());
        doc.meta("DETAIL", voucher.description());
        doc.rule();
        doc.bigRow("AMOUNT", money(voucher.amount()));
        doc.rule();
        doc.meta("RECORDED BY", "#" + voucher.operatorId());
        doc.signature("SIGNATURE");
        return doc.cut();
    }

    // ---- reprint ------------------------------------------------------------------------------

    /**
     * The reprint band (design.md §5 P1). The original's stored bytes are not re-rendered — they
     * are the paper that was printed, and invariant §5.5 says they stay that way — so the band is
     * built as its own fragment and prefixed to them.
     *
     * <p>Which means the band prints first, before the artifact's own {@code ESC @}: whoever picks
     * the paper off the printer sees why it exists before they see what it is.
     */
    @Override
    public RenderedDocument withReprintBand(RenderedDocument original, ReprintReason reason,
                                            OffsetDateTime at) {
        EscPosDocument doc = EscPosDocument.opening(paper);
        doc.band("REPRINT - " + reason.name());
        doc.centred("Reprinted " + stamp(at));
        doc.centred("The original remains the record of the sale");
        doc.rule();
        RenderedDocument band = doc.fragment();

        byte[] joined = new byte[band.bytes().length + original.bytes().length];
        System.arraycopy(band.bytes(), 0, joined, 0, band.bytes().length);
        System.arraycopy(original.bytes(), 0, joined, band.bytes().length, original.bytes().length);
        return new RenderedDocument(joined, band.text() + "\n" + original.text());
    }

    // ---- shared -------------------------------------------------------------------------------

    /**
     * The three lines every artifact opens with (design.md §5): the venue at double size, then the
     * address and the phone if the install has them configured.
     */
    private EscPosDocument header() {
        EscPosDocument doc = EscPosDocument.opening(paper);
        doc.title(venueName);
        if (address != null && !address.isBlank()) {
            doc.centred(address);
        }
        if (phone != null && !phone.isBlank()) {
            doc.centred(phone);
        }
        doc.rule();
        return doc;
    }

    /**
     * {@code GAMING 3x30M}, {@code COLD COFFEE x2}. The quantity is only worth printing when there
     * is more than one: a gaming line already says how many blocks it bought, and an {@code x1}
     * after it is noise on a ticket that has 48 characters to spend.
     */
    private static String lineLabel(SaleReceiptPrinting.Line line) {
        return line.qty() > 1 ? "%s x%d".formatted(line.label(), line.qty()) : line.label();
    }

    /** bKash/Nagad print the tail of the TrxID, never the whole reference (invariant §5.12). */
    private static String tenderLabel(SaleReceiptPrinting.Tender tender) {
        if (tender.paymentRef() == null || tender.paymentRef().isBlank()) {
            return tender.method();
        }
        String trimmed = tender.paymentRef().trim();
        String tail = trimmed.length() <= REF_TAIL
                ? trimmed
                : trimmed.substring(trimmed.length() - REF_TAIL);
        return "%s *%s".formatted(tender.method(), tail);
    }

    private static String money(int amount) {
        return "%s%s %s".formatted(amount < 0 ? "-" : "", CURRENCY, plain(Math.abs(amount)));
    }

    /** Grouped digits with no currency mark — the takings grid says {@code Tk} once, in its heading. */
    private static String plain(int amount) {
        return String.format("%,d", amount);
    }

    private static int nullSafe(Integer amount) {
        return amount == null ? 0 : amount;
    }

    /**
     * Every stamp on paper is venue time (invariant §5.1). The instants arrive as
     * {@code OffsetDateTime} and their offset is whatever wrote them; the counter reads one clock,
     * so they are all moved onto it here rather than trusted to already be on it.
     */
    private static String stamp(OffsetDateTime at) {
        return STAMP.format(at.atZoneSameInstant(VenueTime.ZONE));
    }
}
