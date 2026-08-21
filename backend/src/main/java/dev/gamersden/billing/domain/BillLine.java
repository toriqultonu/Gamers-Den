package dev.gamersden.billing.domain;

/**
 * One chargeable line. Every line on a bill is money the customer still owes: prepaid and
 * already-settled time never becomes a line, it becomes the bill's {@code prepaidCredit}.
 *
 * @param refId the row behind the line — item id, tournament entry id — or {@code null} for gaming,
 *              which is a group of blocks rather than a single row
 * @param qty   how many units: blocks for gaming, units for F&amp;B, 1 for an entry fee
 */
public record BillLine(BillLineKind kind, Long refId, String label, int qty, int unitPrice) {

    public static BillLine gaming(int blocks, int blockPrice) {
        return new BillLine(BillLineKind.GAMING, null, "Gaming", blocks, blockPrice);
    }

    public int amount() {
        return qty * unitPrice;
    }
}
