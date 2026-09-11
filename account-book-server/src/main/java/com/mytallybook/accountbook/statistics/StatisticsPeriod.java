package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public record StatisticsPeriod(String monthText, LocalDate startInclusive, LocalDate endExclusive,
                               LocalDate endInclusive, int selectedDays, RangeType rangeType) {
    public enum RangeType { MONTH, RANGE, ALL }

    private static final ZoneId LEDGER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern MONTH_TEXT = Pattern.compile("[1-9]\\d{3}-(?:0[1-9]|1[0-2])");
    private static final Pattern DATE_TEXT = Pattern.compile("[1-9]\\d{3}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE
            .withResolverStyle(ResolverStyle.STRICT);
    private static final LocalDate MIN_DATE = LocalDate.of(1000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    public static StatisticsPeriod parse(String text, Clock clock) {
        return parse(text, null, null, null, clock);
    }

    public static StatisticsPeriod parse(String monthText, String range, String startDate, String endDate, Clock clock) {
        boolean hasMonth = monthText != null;
        boolean hasRange = range != null;
        boolean hasStart = startDate != null;
        boolean hasEnd = endDate != null;
        boolean hasDates = hasStart || hasEnd;
        int selectors = (hasMonth ? 1 : 0) + (hasRange ? 1 : 0) + (hasDates ? 1 : 0);
        if (selectors > 1) throw invalid();
        if (hasRange) {
            if (!"all".equals(range)) throw invalid();
            return new StatisticsPeriod(null, null, null, null, 0, RangeType.ALL);
        }
        if (hasDates) {
            if (!hasStart || !hasEnd) throw invalid();
            LocalDate start = parseDate(startDate);
            LocalDate end = parseDate(endDate);
            if (end.isBefore(start)) throw invalid();
            return new StatisticsPeriod(null, start, exclusiveAfter(end), end,
                    Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1), RangeType.RANGE);
        }
        YearMonth month;
        if (monthText == null) {
            month = YearMonth.now(clock.withZone(LEDGER_ZONE));
        } else {
            if (!MONTH_TEXT.matcher(monthText).matches()) throw invalid();
            try {
                month = YearMonth.parse(monthText, MONTH);
            } catch (DateTimeParseException exception) {
                throw invalid();
            }
        }
        if (month.getYear() < 1000 || month.getYear() > 9999) throw invalid();
        LocalDate start = month.atDay(1);
        LocalDate endInclusive = month.atEndOfMonth();
        return new StatisticsPeriod(month.format(MONTH), start, exclusiveAfter(endInclusive),
                endInclusive, month.lengthOfMonth(), RangeType.MONTH);
    }

    public int daysInMonth() {
        return selectedDays;
    }

    public String rangeTypeText() {
        return rangeType.name();
    }

    private static LocalDate parseDate(String text) {
        if (!DATE_TEXT.matcher(text).matches()) throw invalid();
        try {
            LocalDate date = LocalDate.parse(text, DATE);
            if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) throw invalid();
            return date;
        } catch (DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static LocalDate exclusiveAfter(LocalDate inclusive) {
        return inclusive.equals(MAX_DATE) ? null : inclusive.plusDays(1);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
