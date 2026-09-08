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
}
