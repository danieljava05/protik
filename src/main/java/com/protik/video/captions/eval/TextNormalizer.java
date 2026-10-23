package com.protik.video.captions.eval;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The text normalisation of the evaluation protocol (ADR 0004, ADR 0023): the same rules are applied to the reference and
 * to the machine's output before any error is counted, so the score is not changed by capital letters or punctuation.
 *
 * <p>Diacritics are a policy, not a detail: in Yoruba the tone marks and dots under letters change the word. Scores are
 * therefore always reported twice, once keeping them and once with them removed.
 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    /**
     * Lower case, one space between words, no punctuation except apostrophes inside words (Hausa, Pidgin and English use
     * them). Digits are kept as they are, because converting "5" to "five" is a modelling choice this tool must not make.
     */
    public static String normalize(String text, boolean keepDiacritics) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        if (!keepDiacritics) {
            s = s.replaceAll("\\p{M}+", "");
        }
        s = s.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'');
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
                    || Character.getType(cp) == Character.COMBINING_SPACING_MARK) {
                out.appendCodePoint(cp);
            } else if (cp == '\'' && out.length() > 0 && Character.isLetterOrDigit(out.codePointBefore(out.length()))
                    && i < s.length() && Character.isLetterOrDigit(s.codePointAt(i))) {
                out.append('\'');
            } else {
                out.append(' ');
            }
        }
        return Normalizer.normalize(out.toString().trim().replaceAll("\\s+", " "), Normalizer.Form.NFC);
    }
}
