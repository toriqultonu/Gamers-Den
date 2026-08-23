package dev.gamersden.support;

import dev.gamersden.printing.domain.RenderedDocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden files for the ESC/POS templates (ARCHITECTURE.md §7). Two files per case: the character
 * grid a cashier sees on S11, and a hex dump of the bytes the printer sees.
 *
 * <p>Both are checked because they fail differently. The text catches a template that started
 * saying the wrong thing — a missing policy line, a token that lost its padding, a column that
 * stopped lining up. The hex catches everything the text cannot show: a band that stopped being
 * inverted, a {@code TOKEN #NN} that lost its double height, a QR that quietly dropped to model 1,
 * a cut that went missing. Invariant §5.5 says these bytes are rendered once and re-sent verbatim
 * on a retry, so a change to them is a change to paper that has already been handed to customers,
 * and it should have to be approved rather than noticed.
 *
 * <p>Regenerate deliberately with {@code -Dgolden.update=true}, then read the diff.
 */
public final class GoldenPaper {

    private static final Path ROOT = Path.of("src", "test", "resources", "golden");

    private static final boolean UPDATE = Boolean.getBoolean("golden.update");

    /** Bytes per line of the hex dump — wide enough to skim, narrow enough to diff. */
    private static final int DUMP_WIDTH = 24;

    private GoldenPaper() {
    }

    /** Asserts one render against its pair of golden files, by name (e.g. {@code p1-sale}). */
    public static void assertMatches(String name, RenderedDocument rendered) {
        compare(name + ".txt", rendered.text(),
                "the 48-column preview S11 shows for " + name);
        compare(name + ".hex", dump(rendered.bytes()),
                "the ESC/POS bytes stored on the print job for " + name);
    }

    private static void compare(String file, String actual, String what) {
        Path path = ROOT.resolve(file);
        if (UPDATE) {
            write(path, actual);
            return;
        }
        assertThat(path)
                .withFailMessage("missing golden file %s - run with -Dgolden.update=true to "
                        + "create it, then review the diff", path)
                .exists();
        assertThat(actual)
                .as("%s (golden %s; regenerate with -Dgolden.update=true)", what, path)
                .isEqualTo(read(path));
    }

    /** Offset, then bytes, uppercase — the form a printer manual prints its examples in. */
    private static String dump(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < bytes.length; offset += DUMP_WIDTH) {
            out.append("%04X  ".formatted(offset));
            for (int i = offset; i < Math.min(offset + DUMP_WIDTH, bytes.length); i++) {
                out.append("%02X ".formatted(bytes[i]));
            }
            out.setLength(out.length() - 1);
            out.append('\n');
        }
        return out.toString();
    }

    private static String read(Path path) {
        try {
            // Written with LF; git may have handed the working copy back with CRLF.
            return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden file " + path, e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write golden file " + path, e);
        }
    }
}
