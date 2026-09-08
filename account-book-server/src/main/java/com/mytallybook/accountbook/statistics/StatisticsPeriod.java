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
import java.util.regex.Pattern;

public record StatisticsPeriod(String monthText, LocalDate startInclusive,
                               LocalDate endExclusive, int daysInMonth) {
    private static final ZoneId LEDGER_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern MONTH_TEXT = Pattern.compile("[1-9]\\d{3}-(?:0[1-9]|1[0-2])");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM")
            .withResolverStyle(ResolverStyle.STRICT);

    public static StatisticsPeriod parse(String text, Clock clock) {
        YearMonth month;
        if (text == null) {
            month = YearMonth.now(clock.withZone(LEDGER_ZONE));
        } else {
            if (!MONTH_TEXT.matcher(text).matches()) throw invalid();
            try {
                month = YearMonth.parse(text, MONTH);
            } catch (DateTimeParseException exception) {
                throw invalid();
            }
        }
        if (month.getYear() < 1000 || month.getYear() > 9999) throw invalid();
        LocalDate end = month.getYear() == 9999 && month.getMonthValue() == 12
                ? null : month.plusMonths(1).atDay(1);
        return new StatisticsPeriod(month.format(MONTH), month.atDay(1), end, month.lengthOfMonth());
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }
}
