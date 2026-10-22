package com.protik.social.service;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;

/**
 * The content rules of ADR 0017 decision 2: plain text, 1 to 2,200 characters after trimming, no control
 * characters except newline and tab. The text is stored exactly as given and never interpreted as markup;
 * escaping it for display is the client's job.
 */
final class CommentText {

    static final int MAX_LENGTH = 2_200;

    private CommentText() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            throw invalid("A comment needs text.");
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            throw invalid("A comment cannot be empty.");
        }
        // length in characters people see (code points), so one emoji is one character, not two
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw invalid("A comment can have at most " + MAX_LENGTH + " characters.");
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean allowedWhitespace = cp == '\n' || cp == '\t';
            if (Character.isISOControl(cp) && !allowedWhitespace) {
                throw invalid("A comment cannot contain control characters.");
            }
            i += Character.charCount(cp);
        }
        return text;
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemType.VALIDATION_FAILED, detail);
    }
}
