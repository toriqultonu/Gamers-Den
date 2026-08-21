package dev.gamersden.billing.web;

import dev.gamersden.billing.domain.BillLine;
import dev.gamersden.billing.domain.BillLineKind;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One line of the bill panel (design.md S4). {@code amount} is {@code qty × unitPrice}, sent
 * computed so the panel and the receipt can never round it differently.
 *
 * <p>{@code refId} is omitted from the JSON on a gaming line
 * ({@code default-property-inclusion: non_null}) — that line is a group of blocks, not a row.
 */
@Schema(name = "BillLine")
public record BillLineView(BillLineKind kind,
                           Long refId,
                           String label,
                           int qty,
                           int unitPrice,
                           int amount) {

    public static BillLineView of(BillLine line) {
        return new BillLineView(line.kind(), line.refId(), line.label(), line.qty(),
                line.unitPrice(), line.amount());
    }
}
