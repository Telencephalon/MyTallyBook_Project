package com.mytallybook.accountbook.statistics;

import java.util.List;

public final class StatisticsModels {
    private StatisticsModels() {}

    public record MonthlySummary(String month, String income, String expense, String net, long entryCount,
                                 String rangeType, String startDate, String endDate) {
        public MonthlySummary(String month, String income, String expense, String net, long entryCount) {
            this(month, income, expense, net, entryCount, "MONTH", month + "-01", null);
        }
    }
    public record DailyItem(String date, String income, String expense, String net, long entryCount) {}
    public record DailyTrend(String month, List<DailyItem> items, String rangeType, String startDate, String endDate,
                             int page, int pageSize, long totalDays, long totalPages, boolean hasNext) {
        public DailyTrend(String month, List<DailyItem> items) {
            this(month, items, "MONTH", month + "-01", null, 1, 31, items.size(), 1, false);
        }
    }
    public record RankingItem(long id, String name, String amount, String percentage, long entryCount) {}
    public record Ranking(String month, String entryType, String total, List<RankingItem> items,
                          String rangeType, String startDate, String endDate) {
        public Ranking(String month, String entryType, String total, List<RankingItem> items) {
            this(month, entryType, total, items, "MONTH", month + "-01", null);
        }
    }
    public record AccountItem(long id, String name, String income, String expense, String net, long entryCount) {}
    public record AccountStatistics(String month, List<AccountItem> items,
                                    String rangeType, String startDate, String endDate) {
        public AccountStatistics(String month, List<AccountItem> items) {
            this(month, items, "MONTH", month + "-01", null);
        }
    }
}
