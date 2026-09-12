package com.mytallybook.accountbook.common.validation;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Set;
import java.util.regex.Pattern;

public final class BookkeepingValidation {
    public static final long MAX_SAFE_ID = 9_007_199_254_740_991L;
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999.99");
    private static final Pattern MONEY = Pattern.compile("-?(?:0|[1-9]\\d{0,12})(?:\\.\\d{1,2})?");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private BookkeepingValidation() {}

    public static long safeId(long value) {
        if (value <= 0 || value > MAX_SAFE_ID) throw invalid();
        return value;
    }
    public static long version(Long value) {
        if (value == null || value < 0 || value > 4_294_967_295L) throw invalid();
        return value;
    }
    public static BigDecimal money(String text, boolean allowZeroOrNegative) {
        if (text == null || !MONEY.matcher(text).matches()) throw invalid();
        BigDecimal value;
        try { value = new BigDecimal(text); } catch (NumberFormatException exception) { throw invalid(); }
        if (value.abs().compareTo(MAX_MONEY) > 0 || (!allowZeroOrNegative && value.signum() <= 0)) throw invalid();
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
    public static String moneyText(BigDecimal value) {
        if (value == null) throw invalid();
        return value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
    public static LocalDate date(String text) {
        if (text == null || text.length() != 10) throw invalid();
        try {
            LocalDate value = LocalDate.parse(text, DATE);
            if (value.getYear() < 1000 || value.getYear() > 9999) throw invalid();
            return value;
        } catch (DateTimeParseException exception) { throw invalid(); }
    }
    public static String uuid(String text) {
        if (text == null || !UUID_TEXT.matcher(text).matches()) throw invalid();
        try { return java.util.UUID.fromString(text).toString(); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }
    public static String entryNote(String text) {
        if (text == null) return null;
        if (text.codePointCount(0, text.length()) > 500) throw invalid();
        return text;
    }
    public static String personName(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.codePointCount(0, value.length()) > 64) throw invalid();
        return value.isEmpty() ? null : value;
    }
    public static String name(String text) {
        if (text == null) throw invalid();
        String value = text.trim();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 40) throw invalid();
        return value;
    }
    public static int sortNo(Integer value, int defaultValue) {
        int result = value == null ? defaultValue : value;
        if (result < 0 || result > 32767) throw invalid();
        return result;
    }
    public static String oneOf(String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) throw invalid();
        return value;
    }
    public static String nullableIcon(String value) {
        if (value == null) return null;
        String result = value.trim();
        if (!result.matches("[A-Za-z0-9_-]{1,40}")) throw invalid();
        return result;
    }
    public static String nullableColor(String value) {
        if (value == null) return null;
        if (!value.matches("#[0-9A-Fa-f]{6}")) throw invalid();
        return value.toUpperCase();
    }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
