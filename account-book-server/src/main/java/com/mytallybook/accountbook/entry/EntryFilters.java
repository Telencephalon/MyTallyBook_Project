package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.validation.BookkeepingValidation;

import java.time.LocalDate;
import java.util.Set;

public record EntryFilters(LocalDate dateFrom, LocalDate dateTo, LocalDate dateToExclusive,
                           String entryType, Long categoryId, Long accountId, Long createdBy,
                           String keyword, int page, int pageSize, long offset) {
    private static final LocalDate MIN_DATE = LocalDate.of(1000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    private static final Set<String> TYPES = Set.of("INCOME", "EXPENSE");

    public static EntryFilters parse(String dateFrom, String dateTo, String entryType,
                                     String categoryId, String accountId, String createdBy,
                                     String keyword, String page, String pageSize) {
        LocalDate from = optionalDate(dateFrom, MIN_DATE);
        LocalDate to = optionalDate(dateTo, MAX_DATE);
        if (from.isAfter(to)) throw invalid();
        LocalDate exclusive = to.equals(MAX_DATE) ? null : to.plusDays(1);
        String type = blank(entryType) ? null : BookkeepingValidation.oneOf(entryType, TYPES);
        Long category = optionalId(categoryId);
        Long account = optionalId(accountId);
        Long creator = optionalId(createdBy);
        String search = blank(keyword) ? null : keyword;
        if (search != null && search.codePointCount(0, search.length()) > 500) throw invalid();
        int pageNumber = positiveInt(page, 1, Integer.MAX_VALUE);
        int size = positiveInt(pageSize, 20, 50);
        long offset = Math.multiplyExact((long) pageNumber - 1L, size);
        return new EntryFilters(from, to, exclusive, type, category, account, creator,
                search, pageNumber, size, offset);
    }

    private static LocalDate optionalDate(String value, LocalDate fallback) {
        return blank(value) ? fallback : BookkeepingValidation.date(value);
    }

    private static Long optionalId(String value) {
        if (blank(value)) return null;
        if (!value.matches("[1-9]\\d*")) throw invalid();
        try { return BookkeepingValidation.safeId(Long.parseLong(value)); }
        catch (NumberFormatException exception) { throw invalid(); }
    }

    private static int positiveInt(String value, int fallback, int maximum) {
        if (blank(value)) return fallback;
        if (!value.matches("[1-9]\\d*")) throw invalid();
        try {
            int result = Integer.parseInt(value);
            if (result > maximum) throw invalid();
            return result;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
