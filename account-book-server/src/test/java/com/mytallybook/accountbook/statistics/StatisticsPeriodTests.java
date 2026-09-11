package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsPeriodTests {
    @Test
    void missingMonthUsesTheCurrentMonthInAsiaShanghai() {
        var clock = Clock.fixed(Instant.parse("2024-01-31T16:30:00Z"), ZoneOffset.UTC);

        var period = StatisticsPeriod.parse(null, clock);

        assertEquals("2024-02", period.monthText());
        assertEquals(LocalDate.of(2024, 2, 1), period.startInclusive());
        assertEquals(LocalDate.of(2024, 3, 1), period.endExclusive());
        assertEquals(29, period.daysInMonth());
    }

    @Test
    void decemberCrossesTheYearAndMaximumMonthHasNoImpossibleUpperBound() {
        var clock = Clock.systemUTC();

        var december = StatisticsPeriod.parse("2024-12", clock);
        var minimum = StatisticsPeriod.parse("1000-01", clock);
        var maximum = StatisticsPeriod.parse("9999-12", clock);

        assertEquals(LocalDate.of(2025, 1, 1), december.endExclusive());
        assertEquals(LocalDate.of(1000, 1, 1), minimum.startInclusive());
        assertNull(maximum.endExclusive());
        assertEquals(31, maximum.daysInMonth());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2024-1", "24-01", "2024-00", "2024-13", "0999-12", "10000-01", "2024-01-01", " 2024-01"})
    void malformedOrOutOfRangeMonthsAreRejected(String month) {
        var error = assertThrows(BusinessException.class,
                () -> StatisticsPeriod.parse(month, Clock.systemUTC()));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
    }

    @Test
    void customRangeUsesInclusiveEndpointsAcrossMonthYearLeapAndMaximumDate() {
        var leap = StatisticsPeriod.parse(null, null, "2024-02-29", "2024-02-29", Clock.systemUTC());
        var maximum = StatisticsPeriod.parse(null, null, "9999-12-31", "9999-12-31", Clock.systemUTC());

        assertEquals(StatisticsPeriod.RangeType.RANGE, leap.rangeType());
        assertNull(leap.monthText());
        assertEquals(LocalDate.of(2024, 2, 29), leap.startInclusive());
        assertEquals(LocalDate.of(2024, 3, 1), leap.endExclusive());
        assertEquals(LocalDate.of(2024, 2, 29), leap.endInclusive());
        assertEquals(1, leap.selectedDays());
        assertEquals(LocalDate.of(9999, 12, 31), maximum.endInclusive());
        assertNull(maximum.endExclusive());
        assertEquals(1, maximum.selectedDays());
    }

    @Test
    void customRangeCountsTheThreeInclusiveDaysAcrossLeapFebruaryIntoMarch() {
        var period = StatisticsPeriod.parse(null, null, "2024-02-28", "2024-03-01", Clock.systemUTC());

        assertEquals(LocalDate.of(2024, 2, 28), period.startInclusive());
        assertEquals(LocalDate.of(2024, 3, 2), period.endExclusive());
        assertEquals(LocalDate.of(2024, 3, 1), period.endInclusive());
        assertEquals(3, period.selectedDays());
    }

    @Test
    void allRangeHasNoFakeCalendarBounds() {
        var all = StatisticsPeriod.parse(null, "all", null, null, Clock.systemUTC());

        assertEquals(StatisticsPeriod.RangeType.ALL, all.rangeType());
        assertNull(all.monthText());
        assertNull(all.startInclusive());
        assertNull(all.endExclusive());
        assertNull(all.endInclusive());
        assertEquals(0, all.selectedDays());
    }

    @ParameterizedTest
    @ValueSource(strings = {"range=month", "missingStart", "missingEnd", "reversed", "conflictingMonthRange", "conflictingMonthDates", "badDate"})
    void malformedRangeSelectorsAreRejected(String scenario) {
        assertThrows(BusinessException.class, () -> {
            switch (scenario) {
                case "range=month" -> StatisticsPeriod.parse(null, "month", null, null, Clock.systemUTC());
                case "missingStart" -> StatisticsPeriod.parse(null, null, null, "2024-01-01", Clock.systemUTC());
                case "missingEnd" -> StatisticsPeriod.parse(null, null, "2024-01-01", null, Clock.systemUTC());
                case "reversed" -> StatisticsPeriod.parse(null, null, "2024-01-02", "2024-01-01", Clock.systemUTC());
                case "conflictingMonthRange" -> StatisticsPeriod.parse("2024-01", "all", null, null, Clock.systemUTC());
                case "conflictingMonthDates" -> StatisticsPeriod.parse("2024-01", null, "2024-01-01", "2024-01-31", Clock.systemUTC());
                case "badDate" -> StatisticsPeriod.parse(null, null, "2024-02-30", "2024-03-01", Clock.systemUTC());
                default -> throw new AssertionError(scenario);
            }
        });
    }
}
