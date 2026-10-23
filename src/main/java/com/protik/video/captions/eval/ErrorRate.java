package com.protik.video.captions.eval;

import java.util.List;

/** Word and character error counts by edit distance (ADR 0004). */
public final class ErrorRate {

    /** Substitutions, deletions and insertions needed to turn the hypothesis into the reference, and the reference length. */
    public record Counts(int substitutions, int deletions, int insertions, int referenceLength) {

        public int errors() {
            return substitutions + deletions + insertions;
        }

        /** Errors divided by reference length; 0 if both are empty. A reference of length zero has no rate. */
        public double rate() {
            return referenceLength == 0 ? (errors() == 0 ? 0 : Double.NaN) : (double) errors() / referenceLength;
        }

        public Counts plus(Counts other) {
            return new Counts(substitutions + other.substitutions, deletions + other.deletions,
                    insertions + other.insertions, referenceLength + other.referenceLength);
        }
    }

    private ErrorRate() {
    }

    public static Counts words(String reference, String hypothesis) {
        return align(split(reference), split(hypothesis));
    }

    public static Counts characters(String reference, String hypothesis) {
        return align(chars(reference), chars(hypothesis));
    }

    private static List<String> split(String text) {
        return text.isBlank() ? List.of() : List.of(text.strip().split(" "));
    }

    private static List<String> chars(String text) {
        return text.codePoints().mapToObj(Character::toString).toList();
    }

    /** Standard Levenshtein alignment; ties prefer a substitution, then a deletion, so the split is deterministic. */
    static Counts align(List<String> ref, List<String> hyp) {
        int n = ref.size();
        int m = hyp.size();
        int[][] cost = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            cost[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            cost[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int same = cost[i - 1][j - 1] + (ref.get(i - 1).equals(hyp.get(j - 1)) ? 0 : 1);
                cost[i][j] = Math.min(same, Math.min(cost[i - 1][j] + 1, cost[i][j - 1] + 1));
            }
        }
        int s = 0;
        int d = 0;
        int ins = 0;
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + (ref.get(i - 1).equals(hyp.get(j - 1)) ? 0 : 1)) {
                if (!ref.get(i - 1).equals(hyp.get(j - 1))) {
                    s++;
                }
                i--;
                j--;
            } else if (i > 0 && cost[i][j] == cost[i - 1][j] + 1) {
                d++;
                i--;
            } else {
                ins++;
                j--;
            }
        }
        return new Counts(s, d, ins, n);
    }
}
