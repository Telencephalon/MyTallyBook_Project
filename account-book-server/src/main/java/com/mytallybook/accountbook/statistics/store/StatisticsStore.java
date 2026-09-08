package com.mytallybook.accountbook.statistics.store;

import com.mytallybook.accountbook.statistics.StatisticsPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface StatisticsStore {
    SummaryRow summary(StatisticsPeriod period);
    List<DailyRow> daily(StatisticsPeriod period);
    List<RankingRow> categories(StatisticsPeriod period, String entryType);
    List<AccountRow> accounts(StatisticsPeriod period);
    List<RankingRow> members(StatisticsPeriod period, String entryType);

    record SummaryRow(BigDecimal income, BigDecimal expense, long entryCount) {}
    record DailyRow(LocalDate date, BigDecimal income, BigDecimal expense, long entryCount) {}
    record RankingRow(long id, String name, BigDecimal amount, long entryCount) {}
    record AccountRow(long id, String name, BigDecimal income, BigDecimal expense, long entryCount) {}
}
