package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class EntryFiltersTests {
    @Test
    void personNameIsTrimmedAndPreservedWhenApplyingPersonalScope() {
        var filters = EntryFilters.parse(null, null, null, null, null, null, "礼金", "  张三  ", "2", "10");
        var scoped = filters.withCreator(2L);
        assertEquals("张三", scoped.personName());
        assertEquals("礼金", scoped.keyword());
        assertEquals(2L, scoped.createdBy());
        assertEquals(10L, scoped.offset());
        assertNull(EntryFilters.parse(null, null, null, null, null, null, null, "   ", null, null).personName());
    }

    @Test
    void personNameLengthUsesTheSame64CharacterLimitAsBills() {
        String supplementary = new String(Character.toChars(0x20000));
        assertEquals(supplementary.repeat(64), EntryFilters.parse(null, null, null, null, null, null,
                null, supplementary.repeat(64), null, null).personName());
        assertThrows(BusinessException.class, () -> EntryFilters.parse(null, null, null, null, null, null,
                null, "人".repeat(65), null, null));
    }

    @Test
    void defaultsCoverMysqlDateRangeAndFirstPage() {
        var filters = EntryFilters.parse(null, null, null, null, null, null, null, null, null);

        assertEquals(LocalDate.of(1000, 1, 1), filters.dateFrom());
        assertEquals(LocalDate.of(9999, 12, 31), filters.dateTo());
        assertNull(filters.dateToExclusive());
        assertEquals(1, filters.page());
        assertEquals(20, filters.pageSize());
        assertEquals(0L, filters.offset());
    }

    @Test
    void inclusiveOrdinaryEndDateBecomesExclusiveSqlBoundary() {
        var filters = EntryFilters.parse("2026-09-01", "2026-09-06", "EXPENSE",
                "7", "8", "2", "%_\\", "3", "50");

        assertEquals(LocalDate.of(2026, 9, 7), filters.dateToExclusive());
        assertEquals("%_\\", filters.keyword());
        assertEquals(100L, filters.offset());
    }

    @Test
    void invalidRangesTypesIdsAndPaginationAreRejected() {
        assertThrows(BusinessException.class, () -> EntryFilters.parse(
                "2026-09-07", "2026-09-06", null, null, null, null, null, null, null));
        assertThrows(BusinessException.class, () -> EntryFilters.parse(
                null, null, "expense", null, null, null, null, null, null));
        assertThrows(BusinessException.class, () -> EntryFilters.parse(
                null, null, null, "0", null, null, null, null, null));
        assertThrows(BusinessException.class, () -> EntryFilters.parse(
                null, null, null, null, null, null, null, "2147483648", "20"));
        assertThrows(BusinessException.class, () -> EntryFilters.parse(
                null, null, null, null, null, null, null, "1", "51"));
    }
}
