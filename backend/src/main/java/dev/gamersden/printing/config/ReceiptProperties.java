package dev.gamersden.printing.config;

import dev.gamersden.printing.domain.PaperWidth;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gamersden.printing.receipt.*} — what the P1–P7 templates need that is not on the artifact
 * itself: the paper width, and the three header lines every ticket opens with (design.md §5).
 *
 * <p>The header is config rather than a table because it is a property of the venue, not of a
 * terminal — {@code terminal_settings} owns per-terminal choices like {@code receipt_copies}, and
 * a single-venue install has exactly one address. The defaults are the venue's own, taken from the
 * visual reference (<em>Gamers Den.dc.html</em>, the 80 mm ticket panel).
 *
 * @param paperWidth 80 mm unless the confirmed printer model says otherwise (ARCHITECTURE.md §8)
 * @param venueName  the double-size line at the top of every artifact
 * @param address    printed under the name; skipped when blank
 * @param phone      printed under the address; skipped when blank
 */
@ConfigurationProperties(prefix = "gamersden.printing.receipt")
public record ReceiptProperties(
        PaperWidth paperWidth,
        String venueName,
        String address,
        String phone) {

    public ReceiptProperties {
        paperWidth = paperWidth == null ? PaperWidth.MM_80 : paperWidth;
        venueName = venueName == null || venueName.isBlank() ? "GAMER'S DEN" : venueName;
    }
}
