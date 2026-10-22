package com.protik.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protik.feed.service.DataPolicy.State;
import org.junit.jupiter.api.Test;

/** ADR 0018 decision 2: the advice is a pure function of settings and usage. */
class DataPolicyTests {

    private static final long MB = 1_000_000L;

    @Test
    void withNoCapAndSaverOffThereIsNoRestrictionAndTheFullPrefetchAllowance() {
        var p = DataPolicy.compute(false, null, 50_000 * MB, 5 * MB);
        assertThat(p.state()).isEqualTo(State.UNLIMITED);
        assertThat(p.remainingBytes()).isNull();
        assertThat(p.recommendedRendition()).isNull();
        assertThat(p.prefetchBudgetBytes()).isEqualTo(5 * MB);
    }

    @Test
    void underNinetyPercentIsOkWithNoRestriction() {
        var p = DataPolicy.compute(false, 100 * MB, 89 * MB, 5 * MB);
        assertThat(p.state()).isEqualTo(State.OK);
        assertThat(p.remainingBytes()).isEqualTo(11 * MB);
        assertThat(p.recommendedRendition()).isNull();
        assertThat(p.prefetchBudgetBytes()).isEqualTo(5 * MB);
    }

    @Test
    void atNinetyPercentTheStateIsLowAndTheAdviceIsTheCheapestVideo() {
        var p = DataPolicy.compute(false, 100 * MB, 90 * MB, 5 * MB);
        assertThat(p.state()).isEqualTo(State.LOW);
        assertThat(p.recommendedRendition()).isEqualTo("144p");
        assertThat(p.remainingBytes()).isEqualTo(10 * MB);
        assertThat(p.prefetchBudgetBytes()).isEqualTo(5 * MB);
    }

    @Test
    void prefetchNeverExceedsWhatIsLeft() {
        var p = DataPolicy.compute(false, 100 * MB, 98 * MB, 5 * MB);
        assertThat(p.prefetchBudgetBytes()).isEqualTo(2 * MB);
    }

    @Test
    void whenTheCapIsReachedOnlyAudioIsAdvisedAndNothingIsPrefetched() {
        for (long used : new long[] {100 * MB, 101 * MB, 5_000 * MB}) {
            var p = DataPolicy.compute(false, 100 * MB, used, 5 * MB);
            assertThat(p.state()).isEqualTo(State.EXHAUSTED);
            assertThat(p.recommendedRendition()).isEqualTo("audio");
            assertThat(p.prefetchBudgetBytes()).isZero();
            assertThat(p.remainingBytes()).as("never negative").isZero();
        }
    }

    @Test
    void dataSaverForcesTheCheapestVideoAndNoPrefetchWhateverTheBudget() {
        var unlimited = DataPolicy.compute(true, null, 0, 5 * MB);
        assertThat(unlimited.recommendedRendition()).isEqualTo("144p");
        assertThat(unlimited.prefetchBudgetBytes()).isZero();
        assertThat(unlimited.dataSaver()).isTrue();

        var plenty = DataPolicy.compute(true, 10_000 * MB, 0, 5 * MB);
        assertThat(plenty.state()).isEqualTo(State.OK);
        assertThat(plenty.recommendedRendition()).isEqualTo("144p");
        assertThat(plenty.prefetchBudgetBytes()).isZero();
    }

    @Test
    void exhaustedBeatsDataSaverBecauseAudioIsEvenCheaper() {
        var p = DataPolicy.compute(true, 100 * MB, 100 * MB, 5 * MB);
        assertThat(p.recommendedRendition()).isEqualTo("audio");
        assertThat(p.prefetchBudgetBytes()).isZero();
    }

    @Test
    void theStateOnlyEverWorsensAsUsageGrows() {
        State previous = State.OK;
        for (long used = 0; used <= 120 * MB; used += MB / 2) {
            State now = DataPolicy.compute(false, 100 * MB, used, 5 * MB).state();
            assertThat(now.ordinal()).isGreaterThanOrEqualTo(previous.ordinal());
            previous = now;
        }
        assertThat(previous).isEqualTo(State.EXHAUSTED);
    }

    @Test
    void hugeNumbersDoNotOverflow() {
        var p = DataPolicy.compute(false, Long.MAX_VALUE / 2, Long.MAX_VALUE / 4, Long.MAX_VALUE / 8);
        assertThat(p.state()).isEqualTo(State.OK);
        assertThat(p.prefetchBudgetBytes()).isEqualTo(Long.MAX_VALUE / 8);
    }

    @Test
    void nonsenseInputsAreRefused() {
        assertThatThrownBy(() -> DataPolicy.compute(false, 100L, -1, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataPolicy.compute(false, 100L, 0, -5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataPolicy.compute(false, 0L, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DataPolicy.compute(false, -100L, 0, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
