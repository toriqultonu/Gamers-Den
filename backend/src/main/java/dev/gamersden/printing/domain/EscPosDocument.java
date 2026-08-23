package dev.gamersden.printing.domain;

import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.EscPosConst;
import com.github.anastaciocintra.escpos.Style;
import com.github.anastaciocintra.escpos.barcode.BarCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One pass, two outputs: the ESC/POS byte stream that goes to the printer and the character-grid
 * text S11 previews. Every call appends to both, which is the whole point — invariant §5.5 stores
 * them together on the job, and they can only stay honest about each other if there is no way to
 * write to one without writing to the other.
 *
 * <p>The preview cannot show ink. A band is inverted on paper and merely centred in the text, a
 * {@code TOKEN #NN} is double height on paper and ordinary text in the preview, a barcode is bars
 * on paper and its payload in the preview. What the preview <em>can</em> promise is the grid —
 * same columns, same words, same order — so what a cashier reads on screen is what they hand
 * across the counter.
 *
 * <p>Everything is folded to printable ASCII first ({@link #printable}). A thermal printer has a
 * code page, not a font stack, and no ESC/POS code page carries Bengali: a name typed in Bengali
 * is going to come out as substitution characters whatever we do. Folding it in one place means
 * the preview shows the substitution too, instead of promising a name the paper will never print.
 */
public final class EscPosDocument {

    /**
     * Code 128 bar height. design.md §5 asks for 12 mm; at 203 dpi a dot is 0.125 mm, so 12 mm is
     * 96 dots.
     */
    private static final int BARCODE_HEIGHT_DOTS = 96;

    /** QR module size in dots — design.md §5 asks for at least 0.5 mm, which is 4 dots at 203 dpi. */
    private static final int QR_MODULE_DOTS = 4;

    /** Feeds under the last line before the cut — design.md §5: "full cut + 4 feeds per artifact". */
    private static final int FEEDS_BEFORE_CUT = 4;

    /**
     * escpos-coffee wants the Code 128 code-set selector inside the payload; this is what makes
     * the symbol subset B (design.md §5).
     */
    private static final String CODE128_SUBSET_B = "{B";

    /** Width of the label column on a meta row, chosen so {@code CASHIER} and {@code TERMINAL} align. */
    private static final int META_LABEL_WIDTH = 10;

    private final PaperWidth paper;
    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
    private final EscPos escpos = new EscPos(sink);
    private final List<String> preview = new ArrayList<>();

    private EscPosDocument(PaperWidth paper) {
        this.paper = paper;
    }

    /**
     * A fresh artifact: the printer is reset and put on a known code page, so a job never inherits
     * the style the job before it left behind.
     */
    public static EscPosDocument opening(PaperWidth paper) {
        EscPosDocument doc = new EscPosDocument(paper);
        doc.io(() -> doc.escpos.initializePrinter()
                .setCharacterCodeTable(EscPos.CharacterCodeTable.CP437_USA_Standard_Europe));
        return doc;
    }

    // ---- text ---------------------------------------------------------------------------------

    /** The venue name at the top of every artifact — double width and height (design.md §5). */
    public EscPosDocument title(String text) {
        return styled(new Style()
                .setJustification(EscPosConst.Justification.Center)
                .setBold(true)
                .setFontSize(Style.FontSize._2, Style.FontSize._2), text, centre(text));
    }

    /**
     * An inverted band — white on black, edge to edge. P5's "TOURNAMENT ENTRY", P6's
     * "PLAY TICKET", P7's "BOOKING CONFIRMED", and the reprint band.
     *
     * <p>The text is padded out to the full width so the black runs the width of the paper; the
     * preview keeps the padding, so the band occupies the same rectangle in both.
     */
    public EscPosDocument band(String text) {
        String padded = padToWidth(printable(text));
        return styled(new Style()
                .setJustification(EscPosConst.Justification.Center)
                .setBold(true)
                .setColorMode(Style.ColorMode.WhiteOnBlack), padded, padded);
    }

    /** {@code TOKEN #07} and friends — double height, centred (design.md §5 P5/P6). */
    public EscPosDocument emphasised(String text) {
        return styled(new Style()
                .setJustification(EscPosConst.Justification.Center)
                .setBold(true)
                .setFontSize(Style.FontSize._1, Style.FontSize._2), text, centre(text));
    }

    public EscPosDocument centred(String text) {
        for (String part : wrap(printable(text))) {
            styled(new Style().setJustification(EscPosConst.Justification.Center),
                    part, centre(part));
        }
        return this;
    }

    public EscPosDocument line(String text) {
        for (String part : wrap(printable(text))) {
            styled(new Style(), part, part);
        }
        return this;
    }

    /** A horizontal rule across the paper — the dashed dividers on the ticket mock. */
    public EscPosDocument rule() {
        return line("-".repeat(paper.columns()));
    }

    /**
     * A meta row: the label in a fixed-width column, then the value. {@code TXN},
     * {@code STATION}, {@code CASHIER} — the block design.md §5 puts under the header. A label
     * wider than the column keeps its space rather than butting up against the value.
     */
    public EscPosDocument meta(String label, String value) {
        String folded = printable(label);
        String padded = folded.length() >= META_LABEL_WIDTH
                ? folded + " "
                : folded + " ".repeat(META_LABEL_WIDTH - folded.length());
        return line(padded + printable(value));
    }

    /** A money row: label left, amount hard against the right margin. */
    public EscPosDocument row(String label, String value) {
        return line(justify(label, value));
    }

    /** The same row at double height — P1's {@code TOTAL}, P4's amount, P2's discrepancy. */
    public EscPosDocument bigRow(String label, String value) {
        String text = justify(label, value);
        return styled(new Style().setBold(true).setFontSize(Style.FontSize._1, Style.FontSize._2),
                text, text);
    }

    /** The line someone signs on a Z report or an expense voucher (design.md §5). */
    public EscPosDocument signature(String label) {
        String prefix = printable(label) + " ";
        return line(prefix + "_".repeat(Math.max(1, paper.columns() - prefix.length())));
    }

    // ---- symbologies --------------------------------------------------------------------------

    /**
     * Code 128 subset B, native {@code GS k} (design.md §5): the transaction id on P1, the shift
     * id on P2, the queue-entry id on P6.
     *
     * <p>The quiet zone is the roll's. The symbol is centre-justified on paper far wider than the
     * bars, which leaves well over the ten modules design.md asks for on either side.
     *
     * <p>The preview shows the payload instead of the bars. That is what makes a scan checkable
     * against the stored render, and it is what an operator needs when the barcode is the thing
     * that failed to read.
     */
    public EscPosDocument code128(String payload) {
        String data = printable(payload);
        BarCode barcode = new BarCode()
                .setSystem(BarCode.BarCodeSystem.CODE128)
                .setBarCodeSize(paper.barcodeModuleDots(), BARCODE_HEIGHT_DOTS)
                .setHRIPosition(BarCode.BarCodeHRIPosition.NotPrinted_Default)
                .setJustification(EscPosConst.Justification.Center);
        io(() -> escpos.write(barcode, CODE128_SUBSET_B + data));
        preview.add(centre("[CODE128] " + data));
        return this;
    }

    /**
     * QR model 2 at ECC M, native {@code GS ( k} (design.md §5) — P5 only, and its content is the
     * opaque {@code qr_token}, never anything that identifies the player.
     *
     * <p>The command blocks are written out here rather than through escpos-coffee's
     * {@code QRCode} wrapper, and it is worth saying why: that wrapper's {@code QRModel} enum
     * carries the values 48 and 49, while ESC/POS numbers the models 49 (model 1) and 50
     * (model 2). Asking the wrapper for model 2 puts a 49 on the wire and the printer builds a
     * model 1 symbol. design.md is specific about model 2, so the five function blocks — model,
     * module size, error correction, store, print — are emitted directly.
     */
    public EscPosDocument qr(String payload) {
        String data = printable(payload);
        byte[] content = data.getBytes(StandardCharsets.US_ASCII);
        byte[] store = new byte[content.length + 3];
        store[0] = '1';
        store[1] = 'P';
        store[2] = '0';
        System.arraycopy(content, 0, store, 3, content.length);
        io(() -> {
            escpos.write(new Style().setJustification(EscPosConst.Justification.Center), "");
            writeAll(qrFunction(new byte[] {'1', 'A', QR_MODEL_2, 0}));
            writeAll(qrFunction(new byte[] {'1', 'C', QR_MODULE_DOTS}));
            writeAll(qrFunction(new byte[] {'1', 'E', QR_ECC_M}));
            writeAll(qrFunction(store));
            writeAll(qrFunction(new byte[] {'1', 'Q', '0'}));
            escpos.write(new Style(), "");
        });
        preview.add(centre("[QR] " + data));
        return this;
    }

    /** {@code GS ( k} fn 165 parameter — ESC/POS numbers model 2 as 50. */
    private static final byte QR_MODEL_2 = 50;

    /** {@code GS ( k} fn 169 parameter — 48..51 are L, M, Q, H; design.md §5 asks for M. */
    private static final byte QR_ECC_M = 49;

    // ---- closing ------------------------------------------------------------------------------

    /**
     * Finishes the artifact: four feeds clear the tear bar, then a full cut (design.md §5). What
     * comes back is what {@code print_jobs} stores.
     */
    public RenderedDocument cut() {
        io(() -> escpos.feed(FEEDS_BEFORE_CUT).cut(EscPos.CutMode.FULL));
        return finished();
    }

    /**
     * Finishes a piece of an artifact rather than a whole one — the reprint band, which is
     * prefixed to bytes that already carry their own cut.
     */
    public RenderedDocument fragment() {
        return finished();
    }

    private RenderedDocument finished() {
        io(escpos::flush);
        return new RenderedDocument(sink.toByteArray(), String.join("\n", preview));
    }

    // ---- internals ----------------------------------------------------------------------------

    /**
     * Folds to printable ASCII. Everything a template prints goes through here, so the bytes and
     * the preview can never disagree about a character the printer has no glyph for.
     */
    static String printable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder folded = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            folded.append(c >= ' ' && c <= '~' ? c : '?');
        }
        return folded.toString();
    }

    private EscPosDocument styled(Style style, String paperText, String previewText) {
        io(() -> escpos.writeLF(style, printable(paperText)));
        preview.add(previewText);
        return this;
    }

    /**
     * Word-wraps to the paper width. The printer wraps an over-long line whether we ask it to or
     * not, so the preview has to wrap in the same places or it stops being a preview — which is
     * the difference between a 48-column roll and a 32-column one showing the same handover note.
     * A single word wider than the paper is broken rather than left to run off the edge.
     */
    private List<String> wrap(String text) {
        if (text.length() <= paper.columns()) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        String rest = text;
        while (rest.length() > paper.columns()) {
            int cut = rest.lastIndexOf(' ', paper.columns());
            if (cut <= 0) {
                cut = paper.columns();
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            } else {
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut + 1);
            }
        }
        lines.add(rest);
        return lines;
    }

    private String centre(String text) {
        String folded = printable(text);
        int pad = Math.max(0, (paper.columns() - folded.length()) / 2);
        return " ".repeat(pad) + folded;
    }

    private String padToWidth(String text) {
        if (text.length() >= paper.columns()) {
            return text;
        }
        int left = (paper.columns() - text.length()) / 2;
        return " ".repeat(left) + text + " ".repeat(paper.columns() - text.length() - left);
    }

    /** Label left, value right, one space minimum between them; an over-long label loses its tail. */
    private String justify(String label, String value) {
        String right = printable(value);
        String left = printable(label);
        int room = paper.columns() - right.length() - 1;
        if (room < 1) {
            return right;
        }
        if (left.length() > room) {
            left = left.substring(0, room);
        }
        return left + " ".repeat(paper.columns() - left.length() - right.length()) + right;
    }

    /** {@code GS ( k pL pH} plus the function body — the QR command family. */
    private static byte[] qrFunction(byte[] body) {
        byte[] command = new byte[body.length + 5];
        command[0] = 0x1D;
        command[1] = '(';
        command[2] = 'k';
        command[3] = (byte) (body.length & 0xFF);
        command[4] = (byte) ((body.length >> 8) & 0xFF);
        System.arraycopy(body, 0, command, 5, body.length);
        return command;
    }

    private void writeAll(byte[] bytes) throws IOException {
        escpos.write(bytes, 0, bytes.length);
    }

    private void io(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            // The sink is a ByteArrayOutputStream, so this cannot happen — and if it somehow
            // does, the job must not be stored half-rendered.
            throw new UncheckedIOException("ESC/POS render failed", e);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
