package com.mytallybook.accountbook.account.store;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class JdbcAccountStore implements AccountStore {
    private static final String SELECT = """
            SELECT fa.id, fa.name, fa.account_type, fa.initial_balance, fa.sort_no, fa.status, fa.version,
                   fa.initial_balance + COALESCE(SUM(
                       CASE WHEN e.entry_type = 'INCOME' THEN e.amount ELSE -e.amount END
                   ), 0) AS current_balance
            FROM fund_account fa
            LEFT JOIN book_entry e
              ON e.account_id = fa.id
             AND e.ledger_id = fa.ledger_id
             AND e.deleted_at IS NULL
            WHERE fa.ledger_id = 1
            """;
    private static final String GROUP_BY = """
            GROUP BY fa.id, fa.name, fa.account_type, fa.initial_balance,
                     fa.sort_no, fa.status, fa.version
            """;

    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;

    @Autowired
    public JdbcAccountStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider::getIfAvailable);
    }

    JdbcAccountStore(JdbcTemplate jdbcTemplate) {
        this(() -> jdbcTemplate);
    }

    private JdbcAccountStore(Supplier<JdbcTemplate> jdbcTemplateSupplier) {
        this.jdbcTemplateSupplier = jdbcTemplateSupplier;
    }

    @Override
    public List<AccountRow> list(String status) {
        String sql = SELECT + (status == null ? "" : " AND fa.status = ?")
                + " " + GROUP_BY + " ORDER BY fa.sort_no, fa.id";
        return status == null ? jdbc().query(sql, mapper()) : jdbc().query(sql, mapper(), status);
    }

    @Override
    public Optional<AccountRow> find(long id) {
        return jdbc().query(SELECT + " AND fa.id = ? " + GROUP_BY, mapper(), id).stream().findFirst();
    }

    @Override
    public long insert(String name, String type, BigDecimal initialBalance, int sortNo, String status) {
        var keys = new GeneratedKeyHolder();
        int changed = jdbc().update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO fund_account (
                        ledger_id, name, account_type, initial_balance, sort_no, status, version
                    ) VALUES (1, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, type);
            statement.setBigDecimal(3, initialBalance);
            statement.setInt(4, sortNo);
            statement.setString(5, status);
            return statement;
        }, keys);
        if (changed != 1 || keys.getKey() == null) throw new IllegalStateException("Account insert failed");
        return keys.getKey().longValue();
    }

    @Override
    public int update(long id, String name, int sortNo, String status, long version) {
        return jdbc().update("""
                UPDATE fund_account
                SET name = ?, sort_no = ?, status = ?, version = version + 1
                WHERE ledger_id = 1 AND id = ? AND version = ?
                """, name, sortNo, status, id, version);
    }

    @Override
    public long referenceCount(long id) {
        Long count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM book_entry WHERE ledger_id = 1 AND account_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    @Override
    public int delete(long id, long version) {
        return jdbc().update(
                "DELETE FROM fund_account WHERE ledger_id = 1 AND id = ? AND version = ?", id, version);
    }

    private static RowMapper<AccountRow> mapper() {
        return (row, index) -> new AccountRow(row.getLong("id"), row.getString("name"),
                row.getString("account_type"), row.getBigDecimal("initial_balance"),
                row.getBigDecimal("current_balance"), row.getInt("sort_no"),
                row.getString("status"), row.getLong("version"));
    }

    private JdbcTemplate jdbc() {
        var jdbc = jdbcTemplateSupplier.get();
        if (jdbc == null) throw new IllegalStateException("Account persistence requires a datasource");
        return jdbc;
    }
}
