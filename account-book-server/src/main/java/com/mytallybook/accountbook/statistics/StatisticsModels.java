package com.mytallybook.accountbook.statistics;

import java.util.List;

public final class StatisticsModels {
    private StatisticsModels() {}

    public record MonthlySummary(String month, String income, String expense, String net, long entryCount) {}
    public record DailyItem(String date, String income, String expense, String net, long entryCount) {}
    public record DailyTrend(String month, List<DailyItem> items) {}
    public record RankingItem(long id, String name, String amount, String percentage, long entryCount) {}
    public record Ranking(String month, String entryType, String total, List<RankingItem> items) {}
    public record AccountItem(long id, String name, String income, String expense, String net, long entryCount) {}
    public record AccountStatistics(String month, List<AccountItem> items) {}
}
