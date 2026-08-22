package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.util.Money;

import java.time.format.DateTimeFormatter;
import java.util.StringJoiner;

/**
 * The stand-in for P1 until B17 (TASKLIST B10: "render stubbed until B17 — store placeholder bytes
 * behind the same interface").
 *
 * <p>It lays the real receipt content out at the real paper width and emits it as plain text, so
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
        return RenderedDocument.plainText(paper.toString());
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
