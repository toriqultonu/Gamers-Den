package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;
import dev.gamersden.common.util.Money;

import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * The stand-in for P1–P5 until B17 (TASKLIST B10: "render stubbed until B17 — store placeholder
 * bytes behind the same interface"; B11 says the same of the X, Z and voucher templates).
 *
 * <p>It lays the real content out at the real paper width and emits it as plain text, so
 * everything downstream of the renderer is already exercised end to end: the job is created inside
 * the money transaction, the bytes are stored once, {@code rendered_text} previews, a retry
 * re-sends what was stored. What is deliberately missing is only the ESC/POS itself — the
 * double-height TOTAL, the inverted bands, the {@code GS k} Code 128 — which is exactly what B17
 * adds by replacing this bean.
 *
 * <p>{@code PrintingConfig} is the swap: this bean only exists while no other
 * {@link ReceiptRenderer} is registered, so B17 supplies its own and this one steps aside without
 * a caller changing.
 */
public class PlaceholderReceiptRenderer implements ReceiptRenderer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** How much of a provider reference ever reaches paper or a log line (invariant §5.12). */
    private static final int REF_TAIL = 6;

    @Override
    public RenderedDocument renderSale(SaleReceiptPrinting.SaleReceipt receipt) {
        StringJoiner paper = new StringJoiner("\n");
        paper.add(centred("GAMER'S DEN"));
        paper.add(centred("- placeholder render, P1 lands in B17 -"));
        paper.add(rule());
        paper.add(meta("TXN", receipt.publicId()));
        paper.add(meta("AT", STAMP.format(receipt.at())));
        paper.add(meta("STATION", receipt.heading()));
        paper.add(meta("CASHIER", "#" + receipt.operatorId()));
        paper.add(rule());
        receipt.lines().forEach(line -> paper.add(amountRow(
                "%s x%d".formatted(line.label(), line.qty()), line.amount())));
        if (receipt.pointsRedeemed() > 0) {
            paper.add(amountRow("POINTS %dPTS".formatted(receipt.pointsRedeemed()),
                    -receipt.pointsRedeemed()));
        }
        paper.add(rule());
        paper.add(amountRow("TOTAL", receipt.total()));
        receipt.tenders().forEach(tender -> paper.add(amountRow(tenderLabel(tender), tender.amount())));
        if (receipt.pointsEarned() > 0 || receipt.pointsBalance() != null) {
            paper.add(rule());
            paper.add(centred("Points earned %d - balance %d"
                    .formatted(receipt.pointsEarned(),
                            receipt.pointsBalance() == null ? 0 : receipt.pointsBalance())));
        }
        paper.add(rule());
        paper.add(centred(receipt.publicId()));
        receipt.entryStubs().forEach(stub -> paper.add(entryStub(stub)));
        return RenderedDocument.plainText(paper.toString());
    }

    /**
     * P5, appended to the sale receipt in the same job (docs/tournaments.md §7). The real template
     * gives this an inverted band, a double-height {@code TOKEN #NN} and a native {@code GS ( k}
     * QR; here the same content is laid out flat, with the token spelled out so a preview can be
     * read and the QR payload printed as text so the scan path is testable before B17.
     */
    private static String entryStub(SaleReceiptPrinting.EntryStub stub) {
        StringJoiner ticket = new StringJoiner("\n");
        ticket.add(rule());
        ticket.add(centred("TOURNAMENT ENTRY"));
        ticket.add(centred(stub.tournamentName()));
        ticket.add(centred(stub.playerName()));
        ticket.add(centred("TOKEN #%02d".formatted(stub.seed())));
        ticket.add(centred("[QR] " + stub.qrToken()));
        return ticket.toString();
    }

    /**
     * P2 / P3. One template, two headings: the X is the Z minus the drawer, the discrepancy and
     * the signature line (design.md §5), which is why every drawer figure here is nullable and
     * simply absent on an interim read.
     */
    @Override
    public RenderedDocument renderShiftReport(ShiftReportPrinting.ShiftReport report) {
        boolean interim = report.kind() == ShiftReportPrinting.Kind.X;
        StringJoiner paper = new StringJoiner("\n");
        paper.add(centred("GAMER'S DEN"));
        paper.add(centred(interim ? "X REPORT - INTERIM" : "Z REPORT"));
        paper.add(centred("- placeholder render, P2/P3 land in B17 -"));
        paper.add(rule());
        paper.add(meta("SHIFT", "#" + report.shiftId()));
        paper.add(meta("TERMINAL", report.terminal()));
        paper.add(meta("OPERATOR", "#" + report.shiftStaffId()));
        paper.add(meta("OPENED", STAMP.format(report.openedAt())));
        paper.add(meta(interim ? "AS OF" : "CLOSED",
                STAMP.format(report.closedAt() == null ? report.at() : report.closedAt())));
        paper.add(rule());
        paper.add(amountRow("OPENING FLOAT", report.openingFloat()));
        paper.add(rule());
        paper.add(centred("TAKINGS BY METHOD"));
        report.byMethod().forEach(line -> paper.add(takingsRows(line)));
        paper.add(rule());
        paper.add(takingsRows(report.totals()));
        paper.add(rule());
        paper.add(amountRow("POINTS REDEEMED", -report.pointsRedeemed()));
        paper.add(meta("POINTS EARNED", String.valueOf(report.pointsEarned())));
        paper.add(meta("SALES", String.valueOf(report.saleCount())));
        paper.add(meta("REFUNDS", String.valueOf(report.refundCount())));
        paper.add(rule());
        paper.add(centred("EXPENSES"));
        report.expenses().forEach(expense -> paper.add(amountRow(
                "%s (%s)".formatted(expense.description(), expense.category()), -expense.amount())));
        paper.add(amountRow("EXPENSES TOTAL", -report.expensesTotal()));
        if (!interim) {
            paper.add(rule());
            paper.add(amountRow("EXPECTED CASH", nullSafe(report.expectedCash())));
            paper.add(amountRow("COUNTED CASH", nullSafe(report.countedCash())));
            paper.add(amountRow("DISCREPANCY", nullSafe(report.discrepancy())));
            if (report.handoverNote() != null && !report.handoverNote().isBlank()) {
                paper.add(rule());
                paper.add(meta("HANDOVER", report.handoverNote()));
            }
            paper.add(rule());
            paper.add("SIGNATURE " + "_".repeat(RenderedDocument.COLUMNS - "SIGNATURE ".length()));
            paper.add(centred("SHIFT-" + report.shiftId()));
        }
        return RenderedDocument.plainText(paper.toString());
    }

    /** P4 — what someone signs for the money they took out of the drawer (design.md §5). */
    @Override
    public RenderedDocument renderExpenseVoucher(ExpenseVoucherPrinting.ExpenseVoucher voucher) {
        StringJoiner paper = new StringJoiner("\n");
        paper.add(centred("GAMER'S DEN"));
        paper.add(centred("EXPENSE VOUCHER"));
        paper.add(centred("- placeholder render, P4 lands in B17 -"));
        paper.add(rule());
        paper.add(meta("AT", STAMP.format(voucher.at())));
        paper.add(meta("SHIFT", "#" + voucher.shiftId()));
        paper.add(meta("CATEGORY", voucher.category()));
        paper.add(meta("DETAIL", voucher.description()));
        paper.add(rule());
        paper.add(amountRow("AMOUNT", voucher.amount()));
        paper.add(rule());
        paper.add(meta("RECORDED BY", "#" + voucher.operatorId()));
        paper.add("SIGNATURE " + "_".repeat(RenderedDocument.COLUMNS - "SIGNATURE ".length()));
        return RenderedDocument.plainText(paper.toString());
    }

    /**
     * A method's row of the takings matrix. Two printed lines rather than a five-column grid: at
     * 48 characters the real P2 wraps the categories under their method too (design.md §5).
     */
    private static String takingsRows(ShiftReportPrinting.MethodLine line) {
        return amountRow(line.method(), line.total()) + "\n"
                + "  gaming %d - fnb %d - tournament %d - pre-booking %d"
                        .formatted(line.gaming(), line.fnb(), line.tournament(), line.booking());
    }

    private static int nullSafe(Integer amount) {
        return amount == null ? 0 : amount;
    }

    /** bKash/Nagad print the tail of the TrxID, never the whole reference (design.md P1). */
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

    private static String amountRow(String label, int amount) {
        String money = Money.format(amount);
        int gap = Math.max(1, RenderedDocument.COLUMNS - label.length() - money.length());
        return label + " ".repeat(gap) + money;
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
