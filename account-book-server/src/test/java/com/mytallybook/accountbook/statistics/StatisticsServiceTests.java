package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.ledger.LedgerReadGuard;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import com.mytallybook.accountbook.statistics.store.StatisticsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
        when(h.store.daily(any(), any(), any())).thenReturn(List.of(
                new StatisticsStore.DailyRow(LocalDate.of(2024, 2, 29), new BigDecimal("0.20"), BigDecimal.ZERO, 1),
                new StatisticsStore.DailyRow(LocalDate.of(2024, 2, 1), new BigDecimal("100.10"), new BigDecimal("30.05"), 2)));

        var trend = h.service.daily(ACTOR, "2024-02");

        assertEquals(29, trend.items().size());
        assertEquals("2024-02-01", trend.items().getFirst().date());
        assertEquals("100.10", trend.items().getFirst().income());
        assertEquals("70.05", trend.items().getFirst().net());
        assertEquals("0.00", trend.items().get(1).expense());
        assertEquals("2024-02-29", trend.items().getLast().date());
        assertEquals(1, trend.page());
        assertEquals(29, trend.totalDays());
        assertFalse(trend.hasNext());
        verify(h.store).daily(any(), eq(LocalDate.of(2024, 2, 1)), eq(LocalDate.of(2024, 3, 1)));
    }

    @Test
    void customDailyPageZeroFillsOnlyBoundedPageButSummaryUsesWholeRange() {
        var h = harness();
        when(h.store.summary(any())).thenReturn(new StatisticsStore.SummaryRow(
                new BigDecimal("300.00"), new BigDecimal("50.00"), 9));
        when(h.store.daily(any(), any(), any())).thenReturn(List.of(
                new StatisticsStore.DailyRow(LocalDate.of(2024, 2, 15), BigDecimal.ZERO, new BigDecimal("7.00"), 1)));

        var summary = h.service.summary(ACTOR, null, null, "2024-01-15", "2024-03-05");
        var page = h.service.daily(ACTOR, null, null, "2024-01-15", "2024-03-05", "2");

        assertEquals("RANGE", summary.rangeType());
        assertEquals("2024-01-15", summary.startDate());
        assertEquals("2024-03-05", summary.endDate());
        assertEquals("250.00", summary.net());
        assertEquals(20, page.items().size());
        assertEquals("2024-02-15", page.items().getFirst().date());
        assertEquals("7.00", page.items().getFirst().expense());
        assertEquals("2024-03-05", page.items().getLast().date());
        assertEquals(51, page.totalDays());
        assertEquals(2, page.totalPages());
        assertFalse(page.hasNext());
        verify(h.store).summary(argThat(period -> period.rangeType() == StatisticsPeriod.RangeType.RANGE));
        verify(h.store).daily(any(), eq(LocalDate.of(2024, 2, 15)), eq(LocalDate.of(2024, 3, 6)));
    }

    @Test
    void crossYearThirtySevenDayRangeSplitsPagesAtTheExactCalendarAnchor() {
        var h = harness();
        when(h.store.extent(any())).thenReturn(new StatisticsStore.DateExtent(
                LocalDate.of(2024, 12, 15), LocalDate.of(2025, 1, 20)));
        when(h.store.daily(any(), any(), any())).thenReturn(List.of());

        var first = h.service.daily(ACTOR, null, null, "2024-12-15", "2025-01-20", "1");
        var second = h.service.daily(ACTOR, null, null, "2024-12-15", "2025-01-20", "2");

        assertEquals(37, first.totalDays());
        assertEquals(2, first.totalPages());
        assertEquals("2024-12-15", first.items().getFirst().date());
        assertEquals("2025-01-14", first.items().getLast().date());
        assertTrue(first.hasNext());
        assertEquals(6, second.items().size());
        assertEquals("2025-01-15", second.items().getFirst().date());
        assertEquals("2025-01-20", second.items().getLast().date());
        assertFalse(second.hasNext());
        verify(h.store).daily(any(), eq(LocalDate.of(2024, 12, 15)), eq(LocalDate.of(2025, 1, 15)));
        verify(h.store).daily(any(), eq(LocalDate.of(2025, 1, 15)), eq(LocalDate.of(2025, 1, 21)));
    }

    @Test
    void emptyAllHistoryDailyHasNoDatesOrNextPage() {
        var h = harness();
        when(h.store.extent(any())).thenReturn(new StatisticsStore.DateExtent(null, null));

        var trend = h.service.daily(ACTOR, null, "all", null, null, null);

        assertEquals("ALL", trend.rangeType());
        assertNull(trend.month());
        assertNull(trend.startDate());
        assertNull(trend.endDate());
        assertTrue(trend.items().isEmpty());
        assertEquals(0, trend.totalDays());
        assertEquals(0, trend.totalPages());
        assertFalse(trend.hasNext());
        verify(h.store, never()).daily(any(), any(), any());
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
    void allDirectionAggregatesBothIncomeAndExpenseRows() {
        var h = harness();
        when(h.store.categories(any(), isNull())).thenReturn(List.of(
                new StatisticsStore.RankingRow(7, "人情", new BigDecimal("30.00"), 1),
                new StatisticsStore.RankingRow(8, "工资", new BigDecimal("100.00"), 1)));
        var ranking = h.service.categories(ACTOR, "2024-09", "ALL");
        assertEquals("ALL", ranking.entryType());
        assertEquals("130.00", ranking.total());
        verify(h.store).categories(any(), isNull());
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

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MEMBER", "ADMIN"})
    void legacyOverloadsApplyLivePersonalScopeToEveryAggregation(MemberRole role) {
        var h = harness();
        when(h.guard.requireActor(ACTOR)).thenReturn(new MemberStore.MemberState(11, 1, "ACTIVE", role,
                "ACTIVE", "昵称", null, Instant.EPOCH));
        when(h.store.summary(any())).thenReturn(new StatisticsStore.SummaryRow(BigDecimal.ZERO, BigDecimal.TEN, 1));

        h.service.summary(ACTOR, "2024-09");
        h.service.daily(ACTOR, "2024-09");
        h.service.categories(ACTOR, "2024-09", "EXPENSE");
        h.service.accounts(ACTOR, "2024-09");
        h.service.members(ACTOR, "2024-09", "EXPENSE");

        verifyScopedAggregations(h.store, 1L);
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"MEMBER", "ADMIN"})
    void allHistoryMetadataAndDailyZeroFillOnlyRevealPersonalDates(MemberRole role) {
        var h = harness();
        when(h.guard.requireActor(ACTOR)).thenReturn(new MemberStore.MemberState(11, 1, "ACTIVE", role,
                "ACTIVE", "昵称", null, Instant.EPOCH));
        when(h.store.summary(any())).thenReturn(new StatisticsStore.SummaryRow(BigDecimal.ZERO, BigDecimal.TEN, 1));
        when(h.store.extent(any())).thenAnswer(call -> Long.valueOf(1).equals(((StatisticsPeriod) call.getArgument(0)).createdBy())
                ? new StatisticsStore.DateExtent(LocalDate.of(2024, 9, 2), LocalDate.of(2024, 9, 4))
                : new StatisticsStore.DateExtent(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31)));
        when(h.store.daily(any(), any(), any())).thenReturn(List.of(new StatisticsStore.DailyRow(
                LocalDate.of(2024, 9, 4), BigDecimal.ZERO, BigDecimal.TEN, 1)));

        var summary = h.service.summary(ACTOR, null, "all", null, null);
        var daily = h.service.daily(ACTOR, null, "all", null, null, null);
        var categories = h.service.categories(ACTOR, null, "all", null, null, "EXPENSE");
        var accounts = h.service.accounts(ACTOR, null, "all", null, null);
        var members = h.service.members(ACTOR, null, "all", null, null, "EXPENSE");

        assertEquals("2024-09-02", summary.startDate());
        assertEquals("2024-09-04", summary.endDate());
        assertEquals("2024-09-02", categories.startDate());
        assertEquals("2024-09-02", accounts.startDate());
        assertEquals("2024-09-02", members.startDate());
        assertEquals(3, daily.totalDays());
        assertEquals(1, daily.totalPages());
        assertEquals(List.of("2024-09-02", "2024-09-03", "2024-09-04"),
                daily.items().stream().map(StatisticsModels.DailyItem::date).toList());
        assertEquals(List.of("0.00", "0.00", "10.00"),
                daily.items().stream().map(StatisticsModels.DailyItem::expense).toList());
        verifyScopedAggregations(h.store, 1L);
        verify(h.store, times(5)).extent(argThat(period -> Long.valueOf(1).equals(period.createdBy())));
    }

    @Test
    void liveOwnerCanScopeEveryAggregationToAllOrAFormerCreator() {
        var h = harness();
        var staleMemberToken = new CurrentUser(1, 1, 11, MemberRole.MEMBER);
        when(h.guard.requireActor(staleMemberToken)).thenReturn(new MemberStore.MemberState(11, 1, "ACTIVE", MemberRole.OWNER,
                "ACTIVE", "昵称", null, Instant.EPOCH));
        when(h.store.summary(any())).thenReturn(new StatisticsStore.SummaryRow(BigDecimal.ZERO, BigDecimal.TEN, 1));
        when(h.store.extent(any())).thenReturn(new StatisticsStore.DateExtent(LocalDate.of(2024, 9, 2), LocalDate.of(2024, 9, 4)));

        for (String requested : new String[]{null, "99"}) {
            clearInvocations(h.store);
            h.service.summary(staleMemberToken, null, "all", null, null, requested);
            h.service.daily(staleMemberToken, null, "all", null, null, "1", requested);
            h.service.categories(staleMemberToken, null, "all", null, null, "ALL", requested);
            h.service.accounts(staleMemberToken, null, "all", null, null, requested);
            h.service.members(staleMemberToken, null, "all", null, null, "ALL", requested);

            Long expected = requested == null ? null : 99L;
            verifyScopedAggregations(h.store, expected);
            verify(h.store, times(5)).extent(argThat(period -> java.util.Objects.equals(expected, period.createdBy())));
        }
    }

    private static void verifyScopedAggregations(StatisticsStore store, Long creator) {
        verify(store).summary(argThat(period -> java.util.Objects.equals(creator, period.createdBy())));
        verify(store).daily(argThat(period -> java.util.Objects.equals(creator, period.createdBy())), any(), any());
        verify(store).categories(argThat(period -> java.util.Objects.equals(creator, period.createdBy())), any());
        verify(store).accounts(argThat(period -> java.util.Objects.equals(creator, period.createdBy())));
        verify(store).members(argThat(period -> java.util.Objects.equals(creator, period.createdBy())), any());
    }

    private static Harness harness() {
        var guard = mock(LedgerReadGuard.class);
        when(guard.requireActor(ACTOR)).thenReturn(new MemberStore.MemberState(11, 1, "ACTIVE", MemberRole.OWNER,
                "ACTIVE", "昵称", null, Instant.EPOCH));
        var store = mock(StatisticsStore.class);
        return new Harness(guard, store, new StatisticsService(guard, store, CLOCK));
    }

    private record Harness(LedgerReadGuard guard, StatisticsStore store, StatisticsService service) {}
}
