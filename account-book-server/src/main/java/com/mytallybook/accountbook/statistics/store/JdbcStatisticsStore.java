package com.mytallybook.accountbook.statistics.store;

import com.mytallybook.accountbook.statistics.StatisticsPeriod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Repository
public class JdbcStatisticsStore implements StatisticsStore {
    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;

    @Autowired
    public JdbcStatisticsStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider::getIfAvailable);
    }

    JdbcStatisticsStore(JdbcTemplate jdbcTemplate) {
        this(() -> jdbcTemplate);
    }

    private JdbcStatisticsStore(Supplier<JdbcTemplate> jdbcTemplateSupplier) {
        this.jdbcTemplateSupplier = jdbcTemplateSupplier;
    }

    @Override
    public SummaryRow summary(StatisticsPeriod period) {
        var query = periodQuery("""
                SELECT COALESCE(SUM(CASE WHEN e.entry_type='INCOME' THEN e.amount ELSE 0 END),0) AS income,
                       COALESCE(SUM(CASE WHEN e.entry_type='EXPENSE' THEN e.amount ELSE 0 END),0) AS expense,
                       COUNT(*) AS entry_count
                FROM book_entry e WHERE e.ledger_id=1 AND e.deleted_at IS NULL
                """, period);
        return jdbc().query(query.sql, (row, index) -> new SummaryRow(
                amount(row.getBigDecimal("income")), amount(row.getBigDecimal("expense")),
                row.getLong("entry_count")), query.arguments.toArray())
                .stream().findFirst().orElse(new SummaryRow(BigDecimal.ZERO, BigDecimal.ZERO, 0));
    }

    @Override
    public List<DailyRow> daily(StatisticsPeriod period, java.time.LocalDate pageStartInclusive,
                                java.time.LocalDate pageEndExclusive) {
        var query = periodQuery("""
                SELECT e.entry_date,
                       COALESCE(SUM(CASE WHEN e.entry_type='INCOME' THEN e.amount ELSE 0 END),0) AS income,
                       COALESCE(SUM(CASE WHEN e.entry_type='EXPENSE' THEN e.amount ELSE 0 END),0) AS expense,
                       COUNT(*) AS entry_count
                FROM book_entry e WHERE e.ledger_id=1 AND e.deleted_at IS NULL
                """, period);
        query.sql += " AND e.entry_date>=?";
        query.arguments.add(Date.valueOf(pageStartInclusive));
        if (pageEndExclusive != null) {
            query.sql += " AND e.entry_date<?";
            query.arguments.add(Date.valueOf(pageEndExclusive));
        }
        query.sql += " GROUP BY e.entry_date ORDER BY e.entry_date ASC";
        return jdbc().query(query.sql, (row, index) -> new DailyRow(row.getDate("entry_date").toLocalDate(),
                amount(row.getBigDecimal("income")), amount(row.getBigDecimal("expense")),
                row.getLong("entry_count")), query.arguments.toArray());
    }

    @Override
    public List<RankingRow> categories(StatisticsPeriod period, String entryType) {
        var query = periodQuery("""
                SELECT c.id,c.name,SUM(e.amount) AS amount,COUNT(*) AS entry_count
                FROM book_entry e JOIN category c ON c.id=e.category_id AND c.ledger_id=e.ledger_id
                WHERE e.ledger_id=1 AND e.deleted_at IS NULL
                """, period);
        query.sql += " AND e.entry_type=? GROUP BY c.id,c.name ORDER BY amount DESC,c.id ASC";
        query.arguments.add(entryType);
        return jdbc().query(query.sql, rankingMapper(), query.arguments.toArray());
    }

    @Override
    public List<AccountRow> accounts(StatisticsPeriod period) {
        var query = periodQuery("""
                SELECT fa.id,fa.name,
                       COALESCE(SUM(CASE WHEN e.entry_type='INCOME' THEN e.amount ELSE 0 END),0) AS income,
                       COALESCE(SUM(CASE WHEN e.entry_type='EXPENSE' THEN e.amount ELSE 0 END),0) AS expense,
                       COUNT(*) AS entry_count
                FROM book_entry e JOIN fund_account fa ON fa.id=e.account_id AND fa.ledger_id=e.ledger_id
                WHERE e.ledger_id=1 AND e.deleted_at IS NULL
                """, period);
        query.sql += " GROUP BY fa.id,fa.name ORDER BY fa.id ASC";
        return jdbc().query(query.sql, (row, index) -> new AccountRow(row.getLong("id"), row.getString("name"),
                amount(row.getBigDecimal("income")), amount(row.getBigDecimal("expense")),
                row.getLong("entry_count")), query.arguments.toArray());
    }

    @Override
    public List<RankingRow> members(StatisticsPeriod period, String entryType) {
        var query = periodQuery("""
                SELECT e.created_by AS id,
                       COALESCE(NULLIF(TRIM(lm.display_name),''),u.nickname) AS name,
                       SUM(e.amount) AS amount,COUNT(*) AS entry_count
                FROM book_entry e
                JOIN app_user u ON u.id=e.created_by
                LEFT JOIN ledger_member lm ON lm.ledger_id=e.ledger_id AND lm.user_id=e.created_by
                WHERE e.ledger_id=1 AND e.deleted_at IS NULL
                """, period);
        query.sql += " AND e.entry_type=? GROUP BY e.created_by,name ORDER BY amount DESC,e.created_by ASC";
        query.arguments.add(entryType);
        return jdbc().query(query.sql, rankingMapper(), query.arguments.toArray());
    }

    @Override
    public DateExtent extent(StatisticsPeriod period) {
        var query = periodQuery("""
                SELECT MIN(e.entry_date) AS start_date,MAX(e.entry_date) AS end_date
                FROM book_entry e WHERE e.ledger_id=1 AND e.deleted_at IS NULL
                """, period);
        return jdbc().query(query.sql, (row, index) -> new DateExtent(
                row.getDate("start_date") == null ? null : row.getDate("start_date").toLocalDate(),
                row.getDate("end_date") == null ? null : row.getDate("end_date").toLocalDate()),
                query.arguments.toArray()).stream().findFirst().orElse(new DateExtent(null, null));
    }

    private static Query periodQuery(String prefix, StatisticsPeriod period) {
        var arguments = new ArrayList<Object>();
        var sql = new StringBuilder(prefix);
        if (period.rangeType() != StatisticsPeriod.RangeType.ALL) {
            sql.append(" AND e.entry_date>=?");
            arguments.add(Date.valueOf(period.startInclusive()));
            if (period.endExclusive() != null) {
                sql.append(" AND e.entry_date<?");
                arguments.add(Date.valueOf(period.endExclusive()));
            }
        }
        return new Query(sql.toString(), arguments);
    }

    private static RowMapper<RankingRow> rankingMapper() {
        return (row, index) -> new RankingRow(row.getLong("id"), row.getString("name"),
                amount(row.getBigDecimal("amount")), row.getLong("entry_count"));
    }

    private static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private JdbcTemplate jdbc() {
        var jdbc = jdbcTemplateSupplier.get();
        if (jdbc == null) throw new IllegalStateException("Statistics persistence requires a datasource");
        return jdbc;
    }

    private static final class Query {
        private String sql;
        private final List<Object> arguments;
        private Query(String sql, List<Object> arguments) { this.sql = sql; this.arguments = arguments; }
    }
}
