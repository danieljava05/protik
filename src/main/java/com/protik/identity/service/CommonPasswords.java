package com.protik.identity.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A bundled list of common passwords (ADR 0021 decision 5). Not a breach database. */
final class CommonPasswords {

    private static final Set<String> LIST = load();

    private CommonPasswords() {
    }

    static boolean contains(String password) {
        return LIST.contains(password.toLowerCase(Locale.ROOT));
    }

    static int size() {
        return LIST.size();
    }

    private static Set<String> load() {
        try (var in = CommonPasswords.class.getResourceAsStream("/identity/common-passwords.txt")) {
            if (in == null) {
                throw new IllegalStateException("identity/common-passwords.txt is missing from the classpath");
            }
            Set<String> words = new HashSet<>();
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        words.add(line.strip().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return Set.copyOf(words);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
