package com.mytallybook.accountbook.statistics.store;

import com.mytallybook.accountbook.statistics.StatisticsPeriod;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcStatisticsStoreTests {
    @Test
    void everyAggregationUsesLedgerSoftDeleteAndClosedOpenMonthBounds() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcStatisticsStore(jdbc);
        var period = StatisticsPeriod.parse("2024-09", Clock.systemUTC());

        store.summary(period);
        store.daily(period);
        store.categories(period, "EXPENSE");
        store.accounts(period);
        store.members(period, "INCOME");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(5)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        for (String value : sql.getAllValues()) {
            assertThat(normalize(value)).contains("e.ledger_id=1", "e.deleted_at IS NULL",
                    "e.entry_date>=?", "e.entry_date<?").doesNotContain("DATE_FORMAT(e.entry_date");
        }
    }

    @Test
    void maximumMonthOmitsAnUnrepresentableUpperBoundAndBindsOnlyTheLowerDate() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcStatisticsStore(jdbc);

        store.summary(StatisticsPeriod.parse("9999-12", Clock.systemUTC()));

        var sql = ArgumentCaptor.forClass(String.class);
        var arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), arguments.capture());
        assertThat(normalize(sql.getValue())).contains("e.entry_date>=?").doesNotContain("e.entry_date<?");
        assertThat(arguments.getValue()).containsExactly(java.sql.Date.valueOf(LocalDate.of(9999, 12, 1)));
    }

    @Test
    void historicalDictionaryAndRemovedCreatorJoinsAreRetainedWhileInitialBalanceIsExcluded() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcStatisticsStore(jdbc);
        var period = StatisticsPeriod.parse("2024-09", Clock.systemUTC());

        store.categories(period, "EXPENSE");
        store.accounts(period);
        store.members(period, "EXPENSE");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        String categories = normalize(sql.getAllValues().get(0));
        String accounts = normalize(sql.getAllValues().get(1));
        String members = normalize(sql.getAllValues().get(2));
        assertThat(categories).contains("JOIN category c", "c.ledger_id=e.ledger_id")
                .doesNotContain("c.status='ACTIVE'");
        assertThat(accounts).contains("JOIN fund_account fa", "fa.ledger_id=e.ledger_id")
                .doesNotContain("initial_balance", "fa.status='ACTIVE'");
        assertThat(members).contains("JOIN app_user u ON u.id=e.created_by",
                        "LEFT JOIN ledger_member lm ON lm.ledger_id=e.ledger_id AND lm.user_id=e.created_by",
                        "COALESCE(NULLIF(TRIM(lm.display_name),''),u.nickname)")
                .doesNotContain("lm.status='ACTIVE'", "u.status='ACTIVE'");
    }

    @Test
    void rankingsUseAmountDescendingAndIdAscendingTieBreak() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcStatisticsStore(jdbc);
        var period = StatisticsPeriod.parse("2024-09", Clock.systemUTC());

        store.categories(period, "EXPENSE");
        store.members(period, "EXPENSE");

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(normalize(sql.getAllValues().get(0))).contains("ORDER BY amount DESC,c.id ASC");
        assertThat(normalize(sql.getAllValues().get(1))).contains("ORDER BY amount DESC,e.created_by ASC");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*=\\s*", "=").replaceAll("\\s*,\\s*", ",").trim();
    }
}
