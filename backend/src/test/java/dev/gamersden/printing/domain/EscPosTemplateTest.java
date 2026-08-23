package dev.gamersden.printing.domain;

import dev.gamersden.common.spi.ExpenseVoucherPrinting;
import dev.gamersden.common.spi.PlayTicketPrinting;
import dev.gamersden.common.spi.SaleReceiptPrinting;
import dev.gamersden.common.spi.ShiftReportPrinting;
import dev.gamersden.support.GoldenPaper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1–P7 against their golden files (design.md §5, ARCHITECTURE.md §7). Every case renders a fixed
 * artifact and locks both outputs: the character grid and the bytes.
 *
 * <p>Invariant §5.5 is why the bytes are locked and not just the words. These bytes are rendered
 * once, stored on the job, and re-sent verbatim by a retry — the paper a customer is holding and
 * the row an auditor reads come from the same array. A template edit that changes them is a
 * change to documents that have already been handed across a counter, so it has to be approved in
 * a diff rather than discovered on a roll.
 *
 * <p>The assertions either side of each golden name the thing that would have broken. A hex diff
 * proves something moved; it does not say that the QR dropped to model 1 or that the reprint band
 * stopped being inverted, and those are the failures worth reading in the failure message.
 */
class EscPosTemplateTest {

    private static final OffsetDateTime SOLD_AT =
            OffsetDateTime.of(2026, 8, 26, 21, 14, 0, 0, ZoneOffset.ofHours(6));
    private static final OffsetDateTime SHIFT_OPENED =
            OffsetDateTime.of(2026, 8, 26, 14, 0, 0, 0, ZoneOffset.ofHours(6));
    private static final OffsetDateTime SHIFT_CLOSED =
            OffsetDateTime.of(2026, 8, 26, 22, 5, 0, 0, ZoneOffset.ofHours(6));
    private static final OffsetDateTime SLOT_STARTS =
            OffsetDateTime.of(2026, 8, 27, 18, 0, 0, 0, ZoneOffset.ofHours(6));

    /** {@code start_at − cutoff_hours} off the booking's own snapshot — four hours here. */
    private static final OffsetDateTime CANCEL_BY =
            OffsetDateTime.of(2026, 8, 27, 14, 0, 0, 0, ZoneOffset.ofHours(6));

    private static final String QR_TOKEN = "qr_7f3a9c21b48e4d05";

    private final EscPosReceiptRenderer renderer = renderer(PaperWidth.MM_80);

    private static EscPosReceiptRenderer renderer(PaperWidth paper) {
        return new EscPosReceiptRenderer(paper, "GAMER'S DEN", "Jaleshwaritola, Bogura",
                "01711-000000");
    }

    // ---- P1 -----------------------------------------------------------------------------------

    @Nested
    @DisplayName("P1 — sale ticket")
    class SaleTicket {

        @Test
        @DisplayName("a seat settling: meta block, lines, TOTAL, tenders, loyalty, Code 128")
        void stationSale() {
            RenderedDocument paper = renderer.renderSale(sale());

            GoldenPaper.assertMatches("p1-sale", paper);
            assertThat(paper.text())
                    .contains("STATION   PS5-01")
                    .contains("[CODE128] GD-2608-047");
            // design.md §5: bKash and Nagad print the tail of the TrxID, never the reference.
            assertThat(paper.text()).contains("BKASH *884210").doesNotContain("TRX99884210");
        }

        @Test
        @DisplayName("a counter sale is headed TYPE, not STATION (design.md §5)")
        void counterSale() {
            RenderedDocument paper = renderer.renderSale(counterSaleOf(
                    new SaleReceiptPrinting.Line("COLD COFFEE", 2, 300), List.of(), null,
                    List.of()));

            assertThat(paper.text()).contains("TYPE      Counter sale").doesNotContain("STATION");
        }

        @Test
        @DisplayName("the transaction id goes out as native GS k Code 128, subset B")
        void nativeCode128() {
            RenderedDocument paper = renderer.renderSale(sale());

            // GS k 73 <n> {B ... — the subset-B selector is part of the payload the printer reads.
            assertThat(hex(paper)).contains("1D 6B 49 0D 7B 42 47 44 2D 32 36 30 38 2D 30 34 37");
            // GS w 3: a 3-dot module is 0.375 mm at 203 dpi, over design.md's 0.33 mm floor.
            assertThat(hex(paper)).contains("1D 77 03");
            // GS h 96: 96 dots is the 12 mm bar height design.md asks for.
            assertThat(hex(paper)).contains("1D 68 60");
        }

        @Test
        @DisplayName("four feeds and a full cut close the artifact")
        void feedsAndCut() {
            RenderedDocument paper = renderer.renderSale(sale());

            // design.md §5: four feeds clear the tear bar, then GS V 0 - a full cut.
            assertThat(hex(paper)).endsWith("0A 0A 0A 0A 1D 56 30");
        }
    }

    // ---- P2 / P3 ------------------------------------------------------------------------------

    @Nested
    @DisplayName("P2 / P3 — Z and X reports")
    class ShiftReports {

        @Test
        @DisplayName("the Z counts the drawer, carries the tournament and pre-booking lines, and signs")
        void zReport() {
            RenderedDocument paper = renderer.renderShiftReport(shiftReport(ShiftReportPrinting.Kind.Z));

            GoldenPaper.assertMatches("p2-z-report", paper);
            // Reconciliation is structural (invariant §5.7): both columns are on the paper.
            assertThat(paper.text()).contains("TOURN").contains("BOOK");
            assertThat(paper.text())
                    .contains("EXPECTED CASH")
                    .contains("COUNTED CASH")
                    .contains("DISCREPANCY")
                    .contains("SIGNATURE")
                    .contains("[CODE128] SHIFT-7");
        }

        @Test
        @DisplayName("the X is the Z minus the drawer, the signature and the barcode")
        void xReport() {
            RenderedDocument paper = renderer.renderShiftReport(shiftReport(ShiftReportPrinting.Kind.X));

            GoldenPaper.assertMatches("p3-x-report", paper);
            assertThat(paper.text())
                    .contains("X REPORT - INTERIM")
                    .contains("TOURN")
                    .contains("BOOK")
                    .doesNotContain("EXPECTED CASH")
                    .doesNotContain("DISCREPANCY")
                    .doesNotContain("SIGNATURE")
                    .doesNotContain("[CODE128]");
        }

        @Test
        @DisplayName("the 58 mm switch stacks the takings instead of gridding them")
        void narrowRoll() {
            RenderedDocument paper =
                    renderer(PaperWidth.MM_58).renderShiftReport(shiftReport(ShiftReportPrinting.Kind.Z));

            GoldenPaper.assertMatches("p2-z-report-58mm", paper);
            assertThat(paper.text().lines()).allSatisfy(line ->
                    assertThat(line.length()).isLessThanOrEqualTo(PaperWidth.MM_58.columns()));
            assertThat(paper.text()).contains("  tournament").contains("  pre-booking");
            // The narrow roll cannot hold a 3-dot module without clipping the symbol.
            assertThat(hex(paper)).contains("1D 77 02");
        }
    }

    // ---- P4 -----------------------------------------------------------------------------------

    @Test
    @DisplayName("P4 — the expense voucher prints the amount double height and a line to sign")
    void expenseVoucher() {
        RenderedDocument paper = renderer.renderExpenseVoucher(new ExpenseVoucherPrinting
                .ExpenseVoucher(31L, "Bus fare", "OTHER", 120, "counter-1", 3L, 7L, SOLD_AT));

        GoldenPaper.assertMatches("p4-expense-voucher", paper);
        assertThat(paper.text())
                .contains("EXPENSE VOUCHER")
                .contains("CATEGORY  OTHER")
                .contains("DETAIL    Bus fare")
                .contains("SIGNATURE ___");
        // GS ! 01 — double height, single width, on the AMOUNT row.
        assertThat(hex(paper)).contains("1D 21 01");
    }

    // ---- P5 -----------------------------------------------------------------------------------

    @Test
    @DisplayName("P5 — the tournament stub bands, tokens and QRs, on the sale's own job")
    void tournamentStub() {
        RenderedDocument paper = renderer.renderSale(counterSaleOf(
                new SaleReceiptPrinting.Line("ENTRY FRIDAY FIFA", 1, 200),
                List.of(new SaleReceiptPrinting.EntryStub(88L, "Friday FIFA", "Rifat Hasan", 1,
                        QR_TOKEN)),
                null, List.of()));

        GoldenPaper.assertMatches("p5-tournament-stub", paper);
        assertThat(paper.text())
                .contains("TOURNAMENT ENTRY")
                .contains("Friday FIFA")
                .contains("TOKEN #01")
                .contains("[QR] " + QR_TOKEN)
                .contains("Show this ticket at the bracket desk");
        // GS ( k 04 00 '1' 'A' 50 00 — model 2, which escpos-coffee's own enum would get wrong.
        assertThat(hex(paper)).contains("1D 28 6B 04 00 31 41 32 00");
        // Module size 4 dots (0.5 mm) and error correction level M, per design.md §5.
        assertThat(hex(paper)).contains("1D 28 6B 03 00 31 43 04");
        assertThat(hex(paper)).contains("1D 28 6B 03 00 31 45 31");
    }

    // ---- P6 -----------------------------------------------------------------------------------

    @Nested
    @DisplayName("P6 — play ticket")
    class PlayTicket {

        @Test
        @DisplayName("sold at the counter, the stub rides on the receipt that paid for it")
        void soldAtThePos() {
            RenderedDocument paper = renderer.renderSale(counterSaleOf(
                    new SaleReceiptPrinting.Line("PLAY PS4 2x30M", 1, 260), List.of(), null,
                    List.of(new SaleReceiptPrinting.PlayTicketStub(4210L, 7,
                            LocalDate.of(2026, 8, 26), "Nafis Iqbal", "PS4", 2))));

            GoldenPaper.assertMatches("p6-play-ticket-sale", paper);
            assertThat(paper.text())
                    .contains("PLAY TICKET")
                    .doesNotContain("PREBOOKED")
                    .contains("TOKEN #07")
                    .contains("PS4 - 1 H PREPAID")
                    .contains("Tokens reset daily")
                    .contains("[CODE128] 4210");
            assertThat(hex(paper)).contains("1D 6B 49 06 7B 42 34 32 31 30");
        }

        @Test
        @DisplayName("a check-in prints it standalone, headed PREBOOKED — no money changed hands")
        void checkedInBooking() {
            RenderedDocument paper = renderer.renderPlayTicket(new PlayTicketPrinting.PlayTicket(
                    4211L, 8, LocalDate.of(2026, 8, 26), "Rifat Hasan", "PS5", 3, true,
                    "PS5-01", SLOT_STARTS, "counter-1", 3L, SOLD_AT));

            GoldenPaper.assertMatches("p6-play-ticket-checkin", paper);
            assertThat(paper.text())
                    .contains("PLAY TICKET - PREBOOKED")
                    .contains("TOKEN #08")
                    .contains("PS5 - 1.5 H PREPAID")
                    .contains("BOOKED    PS5-01")
                    .contains("SLOT      27/08/2026 18:00")
                    .contains("Tokens reset daily")
                    // The barcode is the entry id, which still resolves after a rollover (§5.10).
                    .contains("[CODE128] 4211");
            assertThat(hex(paper)).contains("1D 6B 49 06 7B 42 34 32 31 31");
        }

        @Test
        @DisplayName("the token is double height on both variants")
        void tokenIsDoubleHeight() {
            RenderedDocument sold = renderer.renderSale(counterSaleOf(
                    new SaleReceiptPrinting.Line("PLAY PS4 2x30M", 1, 260), List.of(), null,
                    List.of(new SaleReceiptPrinting.PlayTicketStub(4210L, 7,
                            LocalDate.of(2026, 8, 26), "Nafis Iqbal", "PS4", 2))));
            RenderedDocument checkedIn = renderer.renderPlayTicket(new PlayTicketPrinting.PlayTicket(
                    4211L, 8, LocalDate.of(2026, 8, 26), "Rifat Hasan", "PS5", 3, true,
                    "PS5-01", SLOT_STARTS, "counter-1", 3L, SOLD_AT));

            assertThat(hex(sold)).contains("1D 21 01");
            assertThat(hex(checkedIn)).contains("1D 21 01");
        }
    }

    // ---- P7 -----------------------------------------------------------------------------------

    @Test
    @DisplayName("P7 — the booking confirmation carries the fee and the policy it was sold under")
    void bookingConfirmation() {
        RenderedDocument paper = renderer.renderSale(counterSaleOf(
                new SaleReceiptPrinting.Line("BOOKING 3x30M", 1, 480), List.of(),
                new SaleReceiptPrinting.BookingStub(12L, "PS5-01", "PS5", "Rifat Hasan",
                        "01711-000000", SLOT_STARTS, 3, 480, 100, CANCEL_BY),
                List.of()));

        GoldenPaper.assertMatches("p7-booking-confirmation", paper);
        assertThat(paper.text())
                .contains("BOOKING CONFIRMED")
                .contains("CONSOLE   PS5-01 - PS5")
                .contains("STARTS    27/08/2026 18:00")
                .contains("PLAY 3x30M")
                .contains("PACKAGE FEE")
                .contains("CANCEL BY 27/08/2026 14:00");
        // The policy line is the cutoff the booking snapshotted, not today's setting (§5.11).
        assertThat(paper.text()).contains("Full refund until 4 h before start");
    }

    // ---- reprint ------------------------------------------------------------------------------

    @Nested
    @DisplayName("reprint band")
    class Reprint {

        @Test
        @DisplayName("the band goes in front of the original's stored bytes, unaltered")
        void bandPrefixesTheOriginal() {
            RenderedDocument original = renderer.renderSale(sale());

            RenderedDocument reprint = renderer.withReprintBand(original, ReprintReason.DISPUTE,
                    SOLD_AT.plusMinutes(26));

            GoldenPaper.assertMatches("p1-sale-reprint", reprint);
            assertThat(reprint.text())
                    .contains("REPRINT - DISPUTE")
                    .contains("Reprinted 26/08/2026 21:40")
                    .endsWith(original.text());
            // Invariant §5.5: the original render is carried through, never recomputed.
            assertThat(hex(reprint)).endsWith(hex(original));
        }

        @Test
        @DisplayName("the band is inverted, so nobody mistakes the copy for the original")
        void bandIsInverted() {
            RenderedDocument reprint = renderer.withReprintBand(renderer.renderSale(sale()),
                    ReprintReason.LOST, SOLD_AT);

            // GS B 01 — white on black; the original that follows resets it with GS B 00.
            assertThat(hex(reprint)).contains("1D 42 01");
        }

        @Test
        @DisplayName("the reason is on the paper, whichever reason it was")
        void everyReasonPrints() {
            for (ReprintReason reason : ReprintReason.values()) {
                RenderedDocument reprint = renderer.withReprintBand(renderer.renderSale(sale()),
                        reason, SOLD_AT);

                assertThat(reprint.text()).contains("REPRINT - " + reason.name());
            }
        }
    }

    // ---- non-printable input ------------------------------------------------------------------

    @Test
    @DisplayName("a name the printer has no glyph for is folded the same way in both outputs")
    void previewShowsWhatThePaperWillSay() {
        RenderedDocument paper = renderer.renderPlayTicket(new PlayTicketPrinting.PlayTicket(
                4212L, 9, LocalDate.of(2026, 8, 26), "রিফাত", "PS5", 2, false,
                null, null, "counter-1", 3L, SOLD_AT));

        // No ESC/POS code page carries Bengali. The preview promises the substitution rather than
        // a name the roll will never print.
        assertThat(paper.text()).contains("Player: ?????");
        assertThat(new String(paper.bytes(), java.nio.charset.StandardCharsets.US_ASCII))
                .contains("Player: ?????");
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static SaleReceiptPrinting.SaleReceipt sale() {
        return new SaleReceiptPrinting.SaleReceipt(4711L, "GD-2608-047", "PS5-01", "counter-1", 3L,
                SOLD_AT,
                List.of(new SaleReceiptPrinting.Line("GAMING 3x30M", 1, 480),
                        new SaleReceiptPrinting.Line("COLD COFFEE", 2, 300)),
                680,
                List.of(new SaleReceiptPrinting.Tender("CASH", 500, null),
                        new SaleReceiptPrinting.Tender("BKASH", 180, "TRX99884210")),
                100, 34, 409, List.of(), null, List.of());
    }

    /** A walk-up: no seat, no basket, no loyalty — just whatever stub the sale issued. */
    private static SaleReceiptPrinting.SaleReceipt counterSaleOf(
            SaleReceiptPrinting.Line line,
            List<SaleReceiptPrinting.EntryStub> entries,
            SaleReceiptPrinting.BookingStub booking,
            List<SaleReceiptPrinting.PlayTicketStub> tickets) {
        return new SaleReceiptPrinting.SaleReceipt(4712L, "GD-2608-048", "Counter sale",
                "counter-1", 3L, SOLD_AT, List.of(line), line.amount(),
                List.of(new SaleReceiptPrinting.Tender("CASH", line.amount(), null)),
                0, 0, null, entries, booking, tickets);
    }

    private static ShiftReportPrinting.ShiftReport shiftReport(ShiftReportPrinting.Kind kind) {
        boolean close = kind == ShiftReportPrinting.Kind.Z;
        return new ShiftReportPrinting.ShiftReport(kind, 7L, "counter-1", 3L, "counter-1", 3L,
                SHIFT_OPENED, close ? SHIFT_CLOSED : null, SHIFT_CLOSED, 2000,
                List.of(new ShiftReportPrinting.MethodLine("CASH", 4800, 1200, 600, 1500, 8100),
                        new ShiftReportPrinting.MethodLine("BKASH", 1200, 0, 0, 0, 1200),
                        new ShiftReportPrinting.MethodLine("NAGAD", 0, 0, 0, 0, 0),
                        new ShiftReportPrinting.MethodLine("WALLET", 0, 300, 0, 0, 300)),
                new ShiftReportPrinting.MethodLine("TOTAL", 6000, 1500, 600, 1500, 9600),
                100, 480, 12, 1,
                List.of(new ShiftReportPrinting.ExpenseLine("Bus fare", "OTHER", 120)), 120,
                close ? 6700 : null, close ? 6650 : null, close ? -50 : null,
                close ? "Two controllers out for repair" : null);
    }

    private static String hex(RenderedDocument rendered) {
        StringBuilder out = new StringBuilder();
        for (byte b : rendered.bytes()) {
            out.append("%02X ".formatted(b));
        }
        return out.toString().trim();
    }
}
