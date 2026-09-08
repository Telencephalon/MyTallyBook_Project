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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

@Service
public class StatisticsService {
    private static final Set<String> ENTRY_TYPES = Set.of("INCOME", "EXPENSE");
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
        var period = StatisticsPeriod.parse(month, clock);
        readGuard.requireActor(actor);
        var row = store.summary(period);
        return new StatisticsModels.MonthlySummary(period.monthText(), money(row.income()), money(row.expense()),
                money(row.income().subtract(row.expense())), row.entryCount());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.DailyTrend daily(CurrentUser actor, String month) {
        var period = StatisticsPeriod.parse(month, clock);
        readGuard.requireActor(actor);
        var rows = new HashMap<java.time.LocalDate, StatisticsStore.DailyRow>();
        store.daily(period).forEach(row -> rows.put(row.date(), row));
        var items = period.startInclusive().datesUntil(period.startInclusive().plusDays(period.daysInMonth()))
                .map(date -> {
                    var row = rows.getOrDefault(date,
                            new StatisticsStore.DailyRow(date, BigDecimal.ZERO, BigDecimal.ZERO, 0));
                    return new StatisticsModels.DailyItem(date.toString(), money(row.income()), money(row.expense()),
                            money(row.income().subtract(row.expense())), row.entryCount());
                }).toList();
        return new StatisticsModels.DailyTrend(period.monthText(), items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.Ranking categories(CurrentUser actor, String month, String entryType) {
        var values = rankingInputs(month, entryType);
        readGuard.requireActor(actor);
        return ranking(values.period, values.entryType, store.categories(values.period, values.entryType));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.AccountStatistics accounts(CurrentUser actor, String month) {
        var period = StatisticsPeriod.parse(month, clock);
        readGuard.requireActor(actor);
        var items = store.accounts(period).stream().map(row -> new StatisticsModels.AccountItem(
                BookkeepingValidation.safeId(row.id()), row.name(), money(row.income()), money(row.expense()),
                money(row.income().subtract(row.expense())), row.entryCount())).toList();
        return new StatisticsModels.AccountStatistics(period.monthText(), items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatisticsModels.Ranking members(CurrentUser actor, String month, String entryType) {
        var values = rankingInputs(month, entryType);
        readGuard.requireActor(actor);
        return ranking(values.period, values.entryType, store.members(values.period, values.entryType));
    }

    private RankingInputs rankingInputs(String month, String rawEntryType) {
        var period = StatisticsPeriod.parse(month, clock);
        String entryType = rawEntryType == null ? "EXPENSE"
                : BookkeepingValidation.oneOf(rawEntryType, ENTRY_TYPES);
        return new RankingInputs(period, entryType);
    }

    private static StatisticsModels.Ranking ranking(StatisticsPeriod period, String entryType,
                                                     List<StatisticsStore.RankingRow> rawRows) {
        var rows = rawRows.stream().sorted(Comparator.comparing(StatisticsStore.RankingRow::amount).reversed()
                .thenComparingLong(StatisticsStore.RankingRow::id)).toList();
        BigDecimal total = rows.stream().map(StatisticsStore.RankingRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var items = rows.stream().map(row -> new StatisticsModels.RankingItem(
                BookkeepingValidation.safeId(row.id()), row.name(), money(row.amount()),
                percentage(row.amount(), total), row.entryCount())).toList();
        return new StatisticsModels.Ranking(period.monthText(), entryType, money(total), items);
    }

    private static String percentage(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) return "0.00";
        return amount.multiply(new BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String money(BigDecimal value) {
        return BookkeepingValidation.moneyText(value);
    }

    private record RankingInputs(StatisticsPeriod period, String entryType) {}
}
