package com.mytallybook.accountbook.entry.store;

import com.mytallybook.accountbook.entry.EntryFilters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class JdbcEntryStore implements EntryStore {
    private static final String SELECT = """
            SELECT e.id, e.entry_type, e.amount, e.category_id, c.name AS category_name,
                   c.status AS category_status, e.account_id, fa.name AS account_name,
                   fa.status AS account_status, e.entry_date, e.note, e.created_by,
                   COALESCE(NULLIF(lm.display_name, ''), u.nickname) AS creator_name,
                   e.created_at, e.updated_at, e.deleted_at, e.client_request_id, e.version
            FROM book_entry e
            JOIN category c ON c.id = e.category_id AND c.ledger_id = e.ledger_id
            JOIN fund_account fa ON fa.id = e.account_id AND fa.ledger_id = e.ledger_id
            JOIN app_user u ON u.id = e.created_by
            LEFT JOIN ledger_member lm ON lm.ledger_id = e.ledger_id AND lm.user_id = e.created_by
            WHERE e.ledger_id = 1
            """;

    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;

    @Autowired
    public JdbcEntryStore(ObjectProvider<JdbcTemplate> provider) { this(provider::getIfAvailable); }
    public JdbcEntryStore(JdbcTemplate jdbcTemplate) { this(() -> jdbcTemplate); }
    private JdbcEntryStore(Supplier<JdbcTemplate> supplier) { this.jdbcTemplateSupplier = supplier; }

    @Override
    public List<EntryRow> list(EntryFilters filters) {
        var query = filtered(filters, SELECT + " AND e.deleted_at IS NULL");
        query.sql.append(" ORDER BY e.entry_date DESC, e.id DESC LIMIT ? OFFSET ?");
        query.arguments.add(filters.pageSize());
        query.arguments.add(filters.offset());
        return jdbc().query(query.sql.toString(), mapper(), query.arguments.toArray());
    }

    @Override
    public long count(EntryFilters filters) {
        var query = filtered(filters, "SELECT COUNT(*) FROM book_entry e WHERE e.ledger_id = 1 AND e.deleted_at IS NULL");
        Long result = jdbc().queryForObject(query.sql.toString(), Long.class, query.arguments.toArray());
        return result == null ? 0 : result;
    }

    @Override
    public Optional<EntryRow> find(long id) {
        return jdbc().query(SELECT + " AND e.deleted_at IS NULL AND e.id = ?", mapper(), id).stream().findFirst();
    }

    @Override
    public Optional<EntryRow> findByClientRequestId(String clientRequestId) {
        return jdbc().query(SELECT + " AND e.client_request_id = ?", mapper(), clientRequestId).stream().findFirst();
    }

    @Override
    public Optional<CategoryReference> findCategory(long id) {
        return jdbc().query("""
                SELECT id, entry_type, name, status FROM category
                WHERE ledger_id = 1 AND id = ?
                """, (row, index) -> new CategoryReference(row.getLong("id"), row.getString("entry_type"),
                row.getString("name"), row.getString("status")), id).stream().findFirst();
    }

    @Override
    public Optional<AccountReference> findAccount(long id) {
        return jdbc().query("""
                SELECT id, name, status FROM fund_account
                WHERE ledger_id = 1 AND id = ?
                """, (row, index) -> new AccountReference(row.getLong("id"), row.getString("name"),
                row.getString("status")), id).stream().findFirst();
    }

    @Override
    public List<CreatorRow> creators() {
        return jdbc().query("""
                SELECT user_id, MAX(display_name) AS display_name
                FROM (
                    SELECT lm.user_id,
                           COALESCE(NULLIF(lm.display_name, ''), u.nickname) AS display_name
                    FROM ledger_member lm
                    JOIN app_user u ON u.id = lm.user_id
                    WHERE lm.ledger_id = 1 AND lm.status = 'ACTIVE' AND u.status = 'ACTIVE'
                    UNION
                    SELECT e.created_by AS user_id,
                           COALESCE(NULLIF(lm.display_name, ''), u.nickname) AS display_name
                    FROM book_entry e
                    JOIN app_user u ON u.id = e.created_by
                    LEFT JOIN ledger_member lm ON lm.ledger_id = e.ledger_id AND lm.user_id = e.created_by
                    WHERE e.ledger_id = 1
                ) creator_options
                GROUP BY user_id
                ORDER BY display_name, user_id
                """, (row, index) -> new CreatorRow(row.getLong("user_id"), row.getString("display_name")));
    }

    @Override
    public long insert(String entryType, BigDecimal amount, long categoryId, long accountId,
                       LocalDate entryDate, String note, String clientRequestId, long actorUserId) {
        var keys = new GeneratedKeyHolder();
        int changed = jdbc().update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO book_entry (
                        ledger_id, entry_type, amount, category_id, account_id,
                        entry_date, note, client_request_id, created_by, updated_by, version
                    ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, entryType);
            statement.setBigDecimal(2, amount);
            statement.setLong(3, categoryId);
            statement.setLong(4, accountId);
            statement.setDate(5, Date.valueOf(entryDate));
            statement.setString(6, note);
            statement.setString(7, clientRequestId);
            statement.setLong(8, actorUserId);
            statement.setLong(9, actorUserId);
            return statement;
        }, keys);
        if (changed != 1 || keys.getKey() == null) throw new IllegalStateException("Entry insert failed");
        return keys.getKey().longValue();
    }

    @Override
    public int update(long id, String entryType, BigDecimal amount, long categoryId, long accountId,
                      LocalDate entryDate, String note, long actorUserId, Instant updatedAt, long version) {
        return jdbc().update("""
                UPDATE book_entry SET entry_type=?,amount=?,category_id=?,account_id=?,entry_date=?,note=?,updated_by=?,updated_at=?,version=version+1
                WHERE ledger_id=1 AND id=? AND deleted_at IS NULL AND version=?
                """, entryType, amount, categoryId, accountId, Date.valueOf(entryDate), note,
                actorUserId, Timestamp.from(updatedAt), id, version);
    }

    @Override
    public int softDelete(long id, Instant deletedAt, long actorUserId, long version) {
        var timestamp = Timestamp.from(deletedAt);
        return jdbc().update("""
                UPDATE book_entry SET deleted_at=?,updated_at=?,updated_by=?,version=version+1
                WHERE ledger_id=1 AND id=? AND deleted_at IS NULL AND version=?
                """, timestamp, timestamp, actorUserId, id, version);
    }

    private static Query filtered(EntryFilters filters, String prefix) {
        var query = new Query(prefix);
        query.sql.append(" AND e.entry_date >= ?");
        query.arguments.add(Date.valueOf(filters.dateFrom()));
        if (filters.dateToExclusive() == null) {
            query.sql.append(" AND e.entry_date <= ?");
            query.arguments.add(Date.valueOf(filters.dateTo()));
        } else {
            query.sql.append(" AND e.entry_date < ?");
            query.arguments.add(Date.valueOf(filters.dateToExclusive()));
        }
        add(query, " AND e.entry_type = ?", filters.entryType());
        add(query, " AND e.category_id = ?", filters.categoryId());
        add(query, " AND e.account_id = ?", filters.accountId());
        add(query, " AND e.created_by = ?", filters.createdBy());
        if (filters.keyword() != null) {
            query.sql.append(" AND e.note LIKE ? ESCAPE '\\\\'");
            query.arguments.add("%" + escapeLike(filters.keyword()) + "%");
        }
        return query;
    }

    private static void add(Query query, String sql, Object value) {
        if (value != null) { query.sql.append(sql); query.arguments.add(value); }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static RowMapper<EntryRow> mapper() {
        return (row, index) -> new EntryRow(row.getLong("id"), row.getString("entry_type"), row.getBigDecimal("amount"),
                row.getLong("category_id"), row.getString("category_name"), row.getString("category_status"),
                row.getLong("account_id"), row.getString("account_name"), row.getString("account_status"),
                row.getDate("entry_date").toLocalDate(), row.getString("note"),
                row.getLong("created_by"), row.getString("creator_name"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), instant(row.getTimestamp("deleted_at")),
                row.getString("client_request_id"), row.getLong("version"));
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private JdbcTemplate jdbc() {
        var jdbc = jdbcTemplateSupplier.get();
        if (jdbc == null) throw new IllegalStateException("Entry persistence requires a datasource");
        return jdbc;
    }
    private static final class Query {
        private final StringBuilder sql;
        private final List<Object> arguments = new ArrayList<>();
        private Query(String prefix) { sql = new StringBuilder(prefix); }
    }
}
