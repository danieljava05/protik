package com.protik.video.captions.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.protik.video.captions.eval.Evaluation.Sample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationTests {

    // ---- normalisation ----

    @Test
    void normalisationIgnoresCaseAndPunctuationButKeepsApostrophesInsideWords() {
        assertThat(TextNormalizer.normalize("Hello,  World!!  It’s a “test”.", true)).isEqualTo("hello world it's a test");
        assertThat(TextNormalizer.normalize("'quoted' don't -- stop", true)).isEqualTo("quoted don't stop");
        assertThat(TextNormalizer.normalize("a-b c_d", true)).isEqualTo("a b c d");
        assertThat(TextNormalizer.normalize("  ", true)).isEmpty();
        assertThat(TextNormalizer.normalize(null, true)).isEmpty();
    }

    @Test
    void numbersAreLeftAlone() {
        assertThat(TextNormalizer.normalize("I have 5 naira", true)).isEqualTo("i have 5 naira");
    }

    @Test
    void yorubaToneMarksAndDotsAreKeptOrRemovedOnPurpose() {
        String text = "Ọmọ náà ṣe dáadáa";
        assertThat(TextNormalizer.normalize(text, true)).isEqualTo("ọmọ náà ṣe dáadáa");
        assertThat(TextNormalizer.normalize(text, false)).isEqualTo("omo naa se daadaa");
        // Composed and decomposed input mean the same thing.
        assertThat(TextNormalizer.normalize("ọ́", true)).isEqualTo(TextNormalizer.normalize("ọ́", true));
    }

    // ---- error counts ----

    @Test
    void countsSubstitutionsDeletionsAndInsertions() {
        assertThat(ErrorRate.words("the cat sat", "the cat sat")).isEqualTo(new ErrorRate.Counts(0, 0, 0, 3));
        assertThat(ErrorRate.words("the cat sat", "the dog sat")).isEqualTo(new ErrorRate.Counts(1, 0, 0, 3));
        assertThat(ErrorRate.words("the cat sat", "the sat")).isEqualTo(new ErrorRate.Counts(0, 1, 0, 3));
        assertThat(ErrorRate.words("the cat sat", "the cat sat down")).isEqualTo(new ErrorRate.Counts(0, 0, 1, 3));
        assertThat(ErrorRate.words("the cat sat", "a dog ran far").errors()).isEqualTo(4);
        assertThat(ErrorRate.words("a b c d", "").rate()).isEqualTo(1.0);
        assertThat(ErrorRate.words("", "x y").rate()).isNaN();
        assertThat(ErrorRate.words("", "").rate()).isZero();
    }

    @Test
    void werCanExceedOneHundredPercent() {
        assertThat(ErrorRate.words("a", "b c d").rate()).isEqualTo(3.0);
    }

    @Test
    void characterErrorRateCountsCharacters() {
        var counts = ErrorRate.characters("kitten", "sitting");
        assertThat(counts.errors()).isEqualTo(3); // the textbook Levenshtein example
        assertThat(counts.referenceLength()).isEqualTo(6);
    }

    // ---- the evaluation ----

    private static List<Sample> samples() {
        return List.of(
                new Sample("yo1", "yo", "Ọmọ náà ṣe dáadáa", "omo naa se daadaa"),
                new Sample("yo2", "yo", "Ẹ káàbọ̀", "Ẹ káàbọ̀"),
                new Sample("ha1", "ha", "ina kwana", "ina kwana"),
                new Sample("ha2", "ha", "na gode sosai", "na gode"),
                new Sample("pcm1", "pcm", "how far", ""));
    }

    @Test
    void scoresEachLanguageUnderBothDiacriticPolicies() {
        var kept = Evaluation.score(samples(), true);
        var removed = Evaluation.score(samples(), false);

        // Yoruba: 4 + 2 reference words. Kept: the first utterance is all wrong (4 errors), the second right.
        assertThat(kept.get("yo").referenceWords()).isEqualTo(6);
        assertThat(kept.get("yo").wer()).isCloseTo(4.0 / 6, within(1e-9));
        assertThat(removed.get("yo").wer()).isZero();

        assertThat(kept.get("ha").wer()).isCloseTo(1.0 / 5, within(1e-9)); // one word missing out of five
        assertThat(kept.get("ha").utterances()).isEqualTo(2);
        assertThat(kept.get("pcm").wer()).isEqualTo(1.0);
        assertThat(kept.keySet()).containsExactly("ha", "pcm", "yo");
    }

    @Test
    void theConfidenceIntervalBracketsTheEstimateAndIsReproducible() {
        List<Sample> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(new Sample("u" + i, "ig", "one two three four", i % 4 == 0 ? "one two three" : "one two three four"));
        }
        var first = Evaluation.score(many, true).get("ig");
        var second = Evaluation.score(many, true).get("ig");
        assertThat(first).isEqualTo(second);
        assertThat(first.wer()).isCloseTo(10.0 / 160, within(1e-9));
        assertThat(first.werLow()).isLessThanOrEqualTo(first.wer());
        assertThat(first.werHigh()).isGreaterThanOrEqualTo(first.wer());
        assertThat(first.werHigh() - first.werLow()).as("an interval, not a point").isPositive();
    }

    @Test
    void aPerfectSetHasAZeroWidthIntervalAndNoFailureGallery() {
        var same = List.of(new Sample("a", "en", "hello there", "hello there"), new Sample("b", "en", "good day", "good day"));
        var score = Evaluation.score(same, true).get("en");
        assertThat(score.wer()).isZero();
        assertThat(score.werLow()).isZero();
        assertThat(score.werHigh()).isZero();
        assertThat(score.worst()).isEmpty();
    }

    @Test
    void emptyReferencesAreSkippedAndAllEmptyGivesNoScore() {
        var score = Evaluation.score(List.of(new Sample("a", "en", "  ", "noise"), new Sample("b", "en", "ok", "ok")), true)
                .get("en");
        assertThat(score.utterances()).isEqualTo(1);
        var none = Evaluation.score(List.of(new Sample("a", "en", "", "noise")), true).get("en");
        assertThat(none.utterances()).isZero();
        assertThat(none.wer()).isNaN();
    }

    @Test
    void theFailureGalleryListsTheWorstUtterancesFirst() {
        var score = Evaluation.score(samples(), true).get("yo");
        assertThat(score.worst().getFirst()).startsWith("yo1 (4 errors in 4 words)");
    }

    @Test
    void theReportShowsBothPoliciesAndTheSampleCounts() {
        String report = Evaluation.report(samples());
        assertThat(report).contains("| yo | kept | 2 | 6 | 66.7% |").contains("| yo | removed | 2 | 6 | 0.0% |")
                .contains("| ha | kept | 2 | 5 | 20.0% |").contains("Worst utterances");
    }

    @Test
    void readsTheTabSeparatedFormatAndRejectsMalformedLines(@TempDir Path dir) throws IOException {
        Path ok = dir.resolve("ok.tsv");
        Files.writeString(ok, "# comment\nu1\tyo\tỌmọ\tomo\n\nu2\tha\tina\tina\n", StandardCharsets.UTF_8);
        assertThat(Evaluation.read(ok)).containsExactly(new Sample("u1", "yo", "Ọmọ", "omo"), new Sample("u2", "ha", "ina", "ina"));
        Path bad = dir.resolve("bad.tsv");
        Files.writeString(bad, "u1\tyo\tonly three fields\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> Evaluation.read(bad)).isInstanceOf(IOException.class).hasMessageContaining("Line 1");
    }
}
