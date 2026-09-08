package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.ledger.LedgerReadGuard;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import com.mytallybook.accountbook.statistics.store.StatisticsStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatisticsServiceTests {
    private static final CurrentUser ACTOR = new CurrentUser(1, 1, 11, MemberRole.OWNER);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-09-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void summaryKeepsExactDecimalMoneyAndCountsOnlyStoreRows() {
        var h = harness();
        when(h.store.summary(any())).thenReturn(new StatisticsStore.SummaryRow(
                new BigDecimal("100.30"), new BigDecimal("30.05"), 3));

        var summary = h.service.summary(ACTOR, "2024-09");

        assertEquals("100.30", summary.income());
        assertEquals("30.05", summary.expense());
        assertEquals("70.25", summary.net());
        assertEquals(3, summary.entryCount());
        verify(h.guard).requireActor(ACTOR);
    }

    @Test
    void dailyTrendZeroFillsEveryLeapFebruaryDayAndKeepsDateOrder() {
        var h = harness();
        when(h.store.daily(any())).thenReturn(List.of(
                new StatisticsStore.DailyRow(LocalDate.of(2024, 2, 29), new BigDecimal("0.20"), BigDecimal.ZERO, 1),
                new StatisticsStore.DailyRow(LocalDate.of(2024, 2, 1), new BigDecimal("100.10"), new BigDecimal("30.05"), 2)));

        var trend = h.service.daily(ACTOR, "2024-02");

        assertEquals(29, trend.items().size());
        assertEquals("2024-02-01", trend.items().getFirst().date());
        assertEquals("100.10", trend.items().getFirst().income());
        assertEquals("70.05", trend.items().getFirst().net());
        assertEquals("0.00", trend.items().get(1).expense());
        assertEquals("2024-02-29", trend.items().getLast().date());
    }

    @Test
    void rankingUsesSelectedDirectionAmountOrderIdTieBreakAndHalfUpPercentages() {
        var h = harness();
        when(h.store.categories(any(), eq("EXPENSE"))).thenReturn(List.of(
                new StatisticsStore.RankingRow(9, "交通", new BigDecimal("10.00"), 1),
                new StatisticsStore.RankingRow(8, "餐饮", new BigDecimal("20.00"), 2),
                new StatisticsStore.RankingRow(7, "旧分类", new BigDecimal("10.00"), 1)));

        var ranking = h.service.categories(ACTOR, "2024-09", null);

        assertEquals("EXPENSE", ranking.entryType());
        assertEquals("40.00", ranking.total());
        assertEquals(List.of(8L, 7L, 9L), ranking.items().stream().map(StatisticsModels.RankingItem::id).toList());
        assertEquals(List.of("50.00", "25.00", "25.00"),
                ranking.items().stream().map(StatisticsModels.RankingItem::percentage).toList());
    }

    @Test
    void emptyMemberRankingReturnsZeroWithoutNan() {
        var h = harness();
        when(h.store.members(any(), eq("INCOME"))).thenReturn(List.of());

        var ranking = h.service.members(ACTOR, "2024-09", "INCOME");

        assertEquals("0.00", ranking.total());
        assertTrue(ranking.items().isEmpty());
    }

    @Test
    void accountRowsExposeMonthlyFlowOnlyAndExactNet() {
        var h = harness();
        when(h.store.accounts(any())).thenReturn(List.of(new StatisticsStore.AccountRow(
                8, "现金", new BigDecimal("100.30"), new BigDecimal("30.05"), 3)));

        var result = h.service.accounts(ACTOR, "2024-09");

        assertEquals("70.25", result.items().getFirst().net());
        assertEquals(3, result.items().getFirst().entryCount());
    }

    @Test
    void invalidDirectionIsRejectedBeforeTheStore() {
        var h = harness();

        var error = assertThrows(BusinessException.class,
                () -> h.service.categories(ACTOR, "2024-09", "expense"));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.errorCode());
        verifyNoInteractions(h.store);
    }

    private static Harness harness() {
        var guard = mock(LedgerReadGuard.class);
        var store = mock(StatisticsStore.class);
        return new Harness(guard, store, new StatisticsService(guard, store, CLOCK));
    }

    private record Harness(LedgerReadGuard guard, StatisticsStore store, StatisticsService service) {}
}
