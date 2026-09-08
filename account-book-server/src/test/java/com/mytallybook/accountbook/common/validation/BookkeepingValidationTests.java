package com.mytallybook.accountbook.common.validation;

import com.mytallybook.accountbook.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class BookkeepingValidationTests {
    @Test void decimalArithmeticAndTextStayExact() {
        assertEquals("0.30", BookkeepingValidation.moneyText(new BigDecimal("0.10").add(new BigDecimal("0.20"))));
        assertEquals("-1.20", BookkeepingValidation.moneyText(new BigDecimal("-1.2")));
    }

    @Test void singleMoneyHonorsDecimalContract() {
        assertEquals(new BigDecimal("9999999999999.99"), BookkeepingValidation.money("9999999999999.99", false));
        assertEquals(new BigDecimal("-9999999999999.99"), BookkeepingValidation.money("-9999999999999.99", true));
        for (String value : new String[]{null, "", "1e2", "1.234", "10000000000000.00", "0", "-0.01", "+1.00", ".50"}) {
            assertThrows(BusinessException.class, () -> BookkeepingValidation.money(value, false), value);
        }
        assertEquals(new BigDecimal("0.00"), BookkeepingValidation.money("0", true));
    }

    @Test void datesAreStrictRealMysqlDates() {
        assertEquals(LocalDate.of(2024, 2, 29), BookkeepingValidation.date("2024-02-29"));
        assertEquals(LocalDate.of(1000, 1, 1), BookkeepingValidation.date("1000-01-01"));
        assertEquals(LocalDate.of(9999, 12, 31), BookkeepingValidation.date("9999-12-31"));
        for (String value : new String[]{null, "2025-02-29", "2024-2-29", "0999-12-31", "10000-01-01"}) {
            assertThrows(BusinessException.class, () -> BookkeepingValidation.date(value), value);
        }
    }

    @Test void idsVersionsNamesAndSortsRejectUnsafeValues() {
        assertEquals(9007199254740991L, BookkeepingValidation.safeId(9007199254740991L));
        assertEquals("现金", BookkeepingValidation.name(" 现金 "));
        assertEquals(32767, BookkeepingValidation.sortNo(32767, 0));
        assertEquals(0, BookkeepingValidation.sortNo(null, 0));
        for (long id : new long[]{0, -1, 9007199254740992L}) assertThrows(BusinessException.class, () -> BookkeepingValidation.safeId(id));
        assertEquals(4_294_967_295L, BookkeepingValidation.version(4_294_967_295L));
        for (Long version : new Long[]{null, -1L, 4_294_967_296L}) assertThrows(BusinessException.class, () -> BookkeepingValidation.version(version));
        assertThrows(BusinessException.class, () -> BookkeepingValidation.name("   "));
        assertThrows(BusinessException.class, () -> BookkeepingValidation.name("甲".repeat(41)));
        assertThrows(BusinessException.class, () -> BookkeepingValidation.sortNo(-1, 0));
        assertThrows(BusinessException.class, () -> BookkeepingValidation.sortNo(32768, 0));
    }
}
