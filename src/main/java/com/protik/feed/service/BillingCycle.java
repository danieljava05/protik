package com.protik.feed.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.Instant;

/**
 * The budget period of a user whose data plan renews on {@code billingDay} (1 to 28, ADR 0018 decision 3).
 * The period starts on the most recent billing day, at 00:00 UTC, and ends (exclusive) on the next.
 */
public record BillingCycle(LocalDate startDate) {

    public static BillingCycle containing(LocalDate today, int billingDay) {
        if (billingDay < 1 || billingDay > 28) {
            throw new IllegalArgumentException("billing day must be between 1 and 28");
        }
        LocalDate thisMonth = today.withDayOfMonth(billingDay);
        return new BillingCycle(today.isBefore(thisMonth) ? thisMonth.minusMonths(1) : thisMonth);
    }

    /** First instant of the period. */
    public Instant start() {
        return startDate.atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC);
    }

    /** First instant of the next period (exclusive end). */
    public Instant end() {
        return startDate.plusMonths(1).atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC);
    }
}
