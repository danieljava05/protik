package com.protik.identity.service;

import com.protik.shared.error.ApiException;
import com.protik.shared.error.ProblemType;

/**
 * NIST SP 800-63B style: length matters, composition rules do not. The upper
 * bound exists so a huge "password" cannot be used to burn CPU in the hash.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    static void check(String password, String username, String email) {
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw invalid("Password must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters.");
        }
        if (password.equalsIgnoreCase(username) || password.equalsIgnoreCase(email)) {
            throw invalid("Password must not be the same as your username or email.");
        }
        if (CommonPasswords.contains(password)) {
            throw invalid("That password is on a list of commonly used passwords. Choose another.");
        }
        if (password.isBlank()) {
            throw invalid("Password must not be blank.");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ProblemType.VALIDATION_FAILED, detail);
    }
}
