package dev.gamersden.member.domain;

/**
 * One spelling per phone number. {@code members.phone} is the natural key behind 409
 * {@code DUPLICATE_PHONE} (api-contract.md, Members) and behind the booking form's member attach,
 * so "017 1234-5678" and "01712345678" must not become two members.
 *
 * <p>Normalisation is deliberately conservative: separators humans type go, digits stay, and a
 * leading {@code +} survives because it carries the country code. Nothing else is rewritten — no
 * guessing at local prefixes, no country defaults.
 */
public final class Phones {

    private Phones() {
    }

    /** The stored and compared form: digits only, with a leading {@code +} kept if it was typed. */
    public static String normalise(String phone) {
        if (phone == null) {
            return "";
        }
        String trimmed = phone.trim();
        StringBuilder digits = new StringBuilder(trimmed.length());
        if (trimmed.startsWith("+")) {
            digits.append('+');
        }
        for (char c : trimmed.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    /** The digits inside a free-text search term; empty when the operator typed a name. */
    public static String digitsIn(String text) {
        String normalised = normalise(text);
        return normalised.startsWith("+") ? normalised.substring(1) : normalised;
    }
}
