package com.protik.video.captions.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Scores a set of transcripts the way ADR 0004 requires: per language, with the utterance count, the word and character
 * error rates under both diacritic policies, a 95% bootstrap confidence interval, and the worst utterances as a failure
 * gallery. One scripted tool, so every number is produced the same way.
 *
 * <p>The input is a UTF-8 tab-separated file with one utterance per line: {@code id, language, reference, hypothesis}.
 * Produce the hypotheses with the model under test, from audio the model was never trained or tuned on.
 */
public final class Evaluation {

    public record Sample(String id, String language, String reference, String hypothesis) {
    }

    /** Scores for one language under one diacritic policy. */
    public record Score(int utterances, int referenceWords, double wer, double werLow, double werHigh, double cer,
            List<String> worst) {
    }

    public static final int BOOTSTRAP_RESAMPLES = 1000;
    public static final long SEED = 20261024L;
    static final int WORST = 5;

    private Evaluation() {
    }

    /** @return language to score; utterances with an empty reference are skipped, because they have no rate */
    public static Map<String, Score> score(List<Sample> samples, boolean keepDiacritics) {
        Map<String, List<Sample>> byLanguage = new TreeMap<>();
        for (Sample sample : samples) {
            byLanguage.computeIfAbsent(sample.language(), k -> new ArrayList<>()).add(sample);
        }
        Map<String, Score> result = new TreeMap<>();
        byLanguage.forEach((language, list) -> result.put(language, scoreLanguage(list, keepDiacritics)));
        return result;
    }

    private static Score scoreLanguage(List<Sample> list, boolean keepDiacritics) {
        List<String> ids = new ArrayList<>();
        List<ErrorRate.Counts> words = new ArrayList<>();
        List<ErrorRate.Counts> chars = new ArrayList<>();
        for (Sample s : list) {
            String ref = TextNormalizer.normalize(s.reference(), keepDiacritics);
            String hyp = TextNormalizer.normalize(s.hypothesis(), keepDiacritics);
            if (ref.isEmpty()) {
                continue;
            }
            ids.add(s.id());
            words.add(ErrorRate.words(ref, hyp));
            chars.add(ErrorRate.characters(ref, hyp));
        }
        if (words.isEmpty()) {
            return new Score(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, List.of());
        }
        ErrorRate.Counts totalWords = words.stream().reduce(new ErrorRate.Counts(0, 0, 0, 0), ErrorRate.Counts::plus);
        ErrorRate.Counts totalChars = chars.stream().reduce(new ErrorRate.Counts(0, 0, 0, 0), ErrorRate.Counts::plus);

        // Bootstrap over utterances: resample utterances with replacement and recompute the corpus rate.
        Random random = new Random(SEED);
        double[] rates = new double[BOOTSTRAP_RESAMPLES];
        int n = words.size();
        for (int b = 0; b < BOOTSTRAP_RESAMPLES; b++) {
            int errors = 0;
            int length = 0;
            for (int k = 0; k < n; k++) {
                var c = words.get(random.nextInt(n));
                errors += c.errors();
                length += c.referenceLength();
            }
            rates[b] = length == 0 ? 0 : (double) errors / length;
        }
        java.util.Arrays.sort(rates);
        double low = rates[(int) Math.floor(0.025 * (BOOTSTRAP_RESAMPLES - 1))];
        double high = rates[(int) Math.ceil(0.975 * (BOOTSTRAP_RESAMPLES - 1))];

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingInt(i -> -words.get(i).errors()).thenComparing(i -> ids.get(i)));
        List<String> worst = order.stream().limit(WORST).filter(i -> words.get(i).errors() > 0)
                .map(i -> ids.get(i) + " (" + words.get(i).errors() + " errors in " + words.get(i).referenceLength() + " words)")
                .toList();
        return new Score(n, totalWords.referenceLength(), totalWords.rate(), low, high, totalChars.rate(), worst);
    }

    /** A Markdown report with both diacritic policies, ready to publish with the model and test-set names around it. */
    public static String report(List<Sample> samples) {
        StringBuilder out = new StringBuilder();
        out.append("| Language | Diacritics | Utterances | Reference words | WER | 95% interval | CER |\n");
        out.append("|---|---|---:|---:|---:|---|---:|\n");
        Map<String, Score> kept = score(samples, true);
        Map<String, Score> removed = score(samples, false);
        for (String language : kept.keySet()) {
            row(out, language, "kept", kept.get(language));
            row(out, language, "removed", removed.get(language));
        }
        out.append("\nWorst utterances (diacritics kept):\n");
        kept.forEach((language, score) -> {
            out.append("- ").append(language).append(": ");
            out.append(score.worst().isEmpty() ? "none with errors" : String.join("; ", score.worst())).append('\n');
        });
        return out.toString();
    }

    private static void row(StringBuilder out, String language, String policy, Score s) {
        out.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %s | %s | %s |%n", language, policy, s.utterances(),
                s.referenceWords(), percent(s.wer()), Double.isNaN(s.werLow()) ? "n/a"
                        : percent(s.werLow()) + " to " + percent(s.werHigh()), percent(s.cer())));
    }

    private static String percent(double rate) {
        return Double.isNaN(rate) ? "n/a" : String.format(Locale.ROOT, "%.1f%%", rate * 100);
    }

    /** Reads {@code id TAB language TAB reference TAB hypothesis} lines. */
    public static List<Sample> read(Path tsv) throws IOException {
        List<Sample> samples = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 4) {
                throw new IOException("Line " + lineNumber + " must have 4 tab-separated fields, not " + parts.length);
            }
            samples.add(new Sample(parts[0], parts[1], parts[2], parts[3]));
        }
        return samples;
    }

    /** {@code java ... Evaluation results.tsv} prints the report. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: Evaluation <results.tsv>  (id, language, reference, hypothesis per line)");
            System.exit(2);
        }
        System.out.println(report(read(Path.of(args[0]))));
    }
}
