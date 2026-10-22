package com.protik.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** ADR 0018 decision 3: the budget period follows the day the user's data plan renews. */
class BillingCycleTests {

    @Test
    void thePeriodStartsOnTheMostRecentBillingDay() {
        assertThat(BillingCycle.containing(LocalDate.of(2026, 10, 20), 15).startDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(BillingCycle.containing(LocalDate.of(2026, 10, 10), 15).startDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void theBillingDayItselfBelongsToTheNewPeriod() {
        assertThat(BillingCycle.containing(LocalDate.of(2026, 10, 15), 15).startDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(BillingCycle.containing(LocalDate.of(2026, 10, 14), 15).startDate()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    void periodsCrossYearAndFebruaryBoundariesCorrectly() {
        assertThat(BillingCycle.containing(LocalDate.of(2026, 1, 5), 28).startDate()).isEqualTo(LocalDate.of(2025, 12, 28));
        assertThat(BillingCycle.containing(LocalDate.of(2026, 3, 1), 28).startDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(BillingCycle.containing(LocalDate.of(2024, 3, 1), 28).startDate()).isEqualTo(LocalDate.of(2024, 2, 28)); // leap year
        assertThat(BillingCycle.containing(LocalDate.of(2026, 12, 31), 1).startDate()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void startAndEndAreMidnightUtcAndTheEndIsTheNextStart() {
        var cycle = BillingCycle.containing(LocalDate.of(2026, 10, 20), 15);
        assertThat(cycle.start().toString()).isEqualTo("2026-10-15T00:00:00Z");
        assertThat(cycle.end().toString()).isEqualTo("2026-11-15T00:00:00Z");
        assertThat(BillingCycle.containing(LocalDate.of(2026, 11, 15), 15).start()).isEqualTo(cycle.end());
    }

    @Test
    void forEveryBillingDayAndEveryDayOfTwoYearsTheDateIsInsideItsPeriodAndPeriodsAreAMonthLong() {
        for (int day = 1; day <= 28; day++) {
            for (LocalDate today = LocalDate.of(2024, 1, 1); today.isBefore(LocalDate.of(2026, 1, 1)); today = today.plusDays(1)) {
                var cycle = BillingCycle.containing(today, day);
                assertThat(cycle.startDate().getDayOfMonth()).isEqualTo(day);
                assertThat(cycle.startDate()).isBeforeOrEqualTo(today);
                assertThat(cycle.startDate().plusMonths(1)).isAfter(today);
                long days = ChronoUnit.DAYS.between(cycle.startDate(), cycle.startDate().plusMonths(1));
                assertThat(days).isBetween(28L, 31L);
                assertThat(Duration.between(cycle.start(), cycle.end()).toDays()).isEqualTo(days);
            }
        }
    }

    @Test
    void theDayMustBeBetween1And28() {
        for (int bad : new int[] {0, -1, 29, 31, 100}) {
            assertThatThrownBy(() -> BillingCycle.containing(LocalDate.of(2026, 10, 1), bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
