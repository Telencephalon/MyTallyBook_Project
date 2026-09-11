package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.common.validation.BookkeepingValidation;
import com.mytallybook.accountbook.ledger.LedgerReadGuard;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.statistics.store.StatisticsStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

@Service
public class StatisticsService {
    private static final Set<String> ENTRY_TYPES = Set.of("INCOME", "EXPENSE");
    private static final int DAILY_PAGE_SIZE = 31;
    private final LedgerReadGuard readGuard;
    private final StatisticsStore store;
    private final Clock clock;

    public StatisticsService(LedgerReadGuard readGuard, StatisticsStore store, Clock clock) {
        this.readGuard = readGuard;
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.MonthlySummary summary(CurrentUser actor, String month) {
        return summary(actor, month, null, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.MonthlySummary summary(CurrentUser actor, String month, String range,
                                                    String startDate, String endDate) {
        var period = StatisticsPeriod.parse(month, range, startDate, endDate, clock);
        readGuard.requireActor(actor);
        var row = store.summary(period);
        var metadata = metadata(period);
        return new StatisticsModels.MonthlySummary(period.monthText(), money(row.income()), money(row.expense()),
                money(row.income().subtract(row.expense())), row.entryCount(), period.rangeTypeText(),
                text(metadata.startDate()), text(metadata.endDate()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.DailyTrend daily(CurrentUser actor, String month) {
        return daily(actor, month, null, null, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.DailyTrend daily(CurrentUser actor, String month, String range,
                                             String startDate, String endDate, String rawPage) {
        var period = StatisticsPeriod.parse(month, range, startDate, endDate, clock);
        int page = parsePage(rawPage);
        readGuard.requireActor(actor);
        var metadata = metadata(period);
        long totalDays = days(metadata);
        long totalPages = totalDays == 0 ? 0 : (totalDays + DAILY_PAGE_SIZE - 1) / DAILY_PAGE_SIZE;
        long offset = (long) (page - 1) * DAILY_PAGE_SIZE;
        if (totalDays == 0 || offset >= totalDays) {
            return new StatisticsModels.DailyTrend(period.monthText(), List.of(), period.rangeTypeText(),
                    text(metadata.startDate()), text(metadata.endDate()), page, DAILY_PAGE_SIZE, totalDays,
                    totalPages, false);
        }
        LocalDate pageStart = metadata.startDate().plusDays(offset);
        int daysOnPage = (int) Math.min(DAILY_PAGE_SIZE, totalDays - offset);
        LocalDate pageEndInclusive = pageStart.plusDays(daysOnPage - 1L);
        LocalDate pageEndExclusive = exclusiveAfter(pageEndInclusive);
        var rows = new HashMap<java.time.LocalDate, StatisticsStore.DailyRow>();
        store.daily(period, pageStart, pageEndExclusive).forEach(row -> rows.put(row.date(), row));
        var items = LongStream.range(0, daysOnPage)
                .mapToObj(pageStart::plusDays)
                .map(date -> {
                    var row = rows.getOrDefault(date,
                            new StatisticsStore.DailyRow(date, BigDecimal.ZERO, BigDecimal.ZERO, 0));
                    return new StatisticsModels.DailyItem(date.toString(), money(row.income()), money(row.expense()),
                            money(row.income().subtract(row.expense())), row.entryCount());
                }).toList();
        return new StatisticsModels.DailyTrend(period.monthText(), items, period.rangeTypeText(),
                text(metadata.startDate()), text(metadata.endDate()), page, DAILY_PAGE_SIZE, totalDays, totalPages,
                page < totalPages);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.Ranking categories(CurrentUser actor, String month, String entryType) {
        return categories(actor, month, null, null, null, entryType);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.Ranking categories(CurrentUser actor, String month, String range,
                                               String startDate, String endDate, String entryType) {
        var values = rankingInputs(month, range, startDate, endDate, entryType);
        readGuard.requireActor(actor);
        return ranking(values.period, values.entryType, metadata(values.period),
                store.categories(values.period, values.entryType));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.AccountStatistics accounts(CurrentUser actor, String month) {
        return accounts(actor, month, null, null, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.AccountStatistics accounts(CurrentUser actor, String month, String range,
                                                       String startDate, String endDate) {
        var period = StatisticsPeriod.parse(month, range, startDate, endDate, clock);
        readGuard.requireActor(actor);
        var items = store.accounts(period).stream().map(row -> new StatisticsModels.AccountItem(
                BookkeepingValidation.safeId(row.id()), row.name(), money(row.income()), money(row.expense()),
                money(row.income().subtract(row.expense())), row.entryCount())).toList();
        var metadata = metadata(period);
        return new StatisticsModels.AccountStatistics(period.monthText(), items, period.rangeTypeText(),
                text(metadata.startDate()), text(metadata.endDate()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.Ranking members(CurrentUser actor, String month, String entryType) {
        return members(actor, month, null, null, null, entryType);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.Ranking members(CurrentUser actor, String month, String range,
                                            String startDate, String endDate, String entryType) {
        var values = rankingInputs(month, range, startDate, endDate, entryType);
        readGuard.requireActor(actor);
        return ranking(values.period, values.entryType, metadata(values.period),
                store.members(values.period, values.entryType));
    }

    private RankingInputs rankingInputs(String month, String range, String startDate, String endDate,
                                        String rawEntryType) {
        var period = StatisticsPeriod.parse(month, range, startDate, endDate, clock);
        String entryType = rawEntryType == null ? "EXPENSE"
                : BookkeepingValidation.oneOf(rawEntryType, ENTRY_TYPES);
        return new RankingInputs(period, entryType);
    }

    private static StatisticsModels.Ranking ranking(StatisticsPeriod period, String entryType,
                                                    PeriodMetadata metadata,
                                                    List<StatisticsStore.RankingRow> rawRows) {
        var rows = rawRows.stream().sorted(Comparator.comparing(StatisticsStore.RankingRow::amount).reversed()
                .thenComparingLong(StatisticsStore.RankingRow::id)).toList();
        BigDecimal total = rows.stream().map(StatisticsStore.RankingRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var items = rows.stream().map(row -> new StatisticsModels.RankingItem(
                BookkeepingValidation.safeId(row.id()), row.name(), money(row.amount()),
                percentage(row.amount(), total), row.entryCount())).toList();
        return new StatisticsModels.Ranking(period.monthText(), entryType, money(total), items,
                period.rangeTypeText(), text(metadata.startDate()), text(metadata.endDate()));
    }

    private PeriodMetadata metadata(StatisticsPeriod period) {
        if (period.rangeType() == StatisticsPeriod.RangeType.ALL) {
            var extent = store.extent(period);
            return new PeriodMetadata(extent.startDate(), extent.endDate());
        }
        return new PeriodMetadata(period.startInclusive(), period.endInclusive());
    }

    private static long days(PeriodMetadata metadata) {
        if (metadata.startDate() == null || metadata.endDate() == null) return 0;
        return ChronoUnit.DAYS.between(metadata.startDate(), metadata.endDate()) + 1;
    }

    private static int parsePage(String rawPage) {
        if (rawPage == null) return 1;
        if (!rawPage.matches("[1-9]\\d*")) throw invalid();
        try {
            int page = Integer.parseInt(rawPage);
            return page;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static LocalDate exclusiveAfter(LocalDate inclusive) {
        return LocalDate.of(9999, 12, 31).equals(inclusive) ? null : inclusive.plusDays(1);
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private static String percentage(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) return "0.00";
        return amount.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String money(BigDecimal value) {
        return BookkeepingValidation.moneyText(value);
    }

    private record RankingInputs(StatisticsPeriod period, String entryType) {}
    private record PeriodMetadata(LocalDate startDate, LocalDate endDate) {}
}
