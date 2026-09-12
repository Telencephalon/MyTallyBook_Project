package com.mytallybook.accountbook.category.store;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Repository
public class JdbcCategoryStore implements CategoryStore {
    private static final String SELECT = """
            SELECT id, entry_type, name, icon, color, sort_no, system_default, status
            FROM category
            WHERE ledger_id = 1
            """;

    private final Supplier<JdbcTemplate> jdbcTemplateSupplier;

    @Autowired
    public JdbcCategoryStore(ObjectProvider<JdbcTemplate> provider) {
        this(provider::getIfAvailable);
    }

    JdbcCategoryStore(JdbcTemplate jdbcTemplate) {
        this(() -> jdbcTemplate);
    }

    private JdbcCategoryStore(Supplier<JdbcTemplate> jdbcTemplateSupplier) {
        this.jdbcTemplateSupplier = jdbcTemplateSupplier;
    }

    @Override
    public List<CategoryRow> list(String entryType, String status) {
        var sql = new StringBuilder(SELECT);
        var arguments = new ArrayList<>();
        if (entryType != null) {
            sql.append(" AND (entry_type = ? OR entry_type = 'BOTH')");
            arguments.add(entryType);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            arguments.add(status);
        }
        sql.append(" ORDER BY sort_no, id");
        return jdbc().query(sql.toString(), mapper(), arguments.toArray());
    }

    @Override
    public Optional<CategoryRow> find(long id) {
        return jdbc().query(SELECT + " AND id = ?", mapper(), id).stream().findFirst();
    }

    @Override
    public long insert(String entryType, String name, String icon, String color, int sortNo, String status) {
        var keys = new GeneratedKeyHolder();
        int changed = jdbc().update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO category (
                        ledger_id, entry_type, name, icon, color, sort_no, system_default, status
                    ) VALUES (1, ?, ?, ?, ?, ?, FALSE, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, entryType);
            statement.setString(2, name);
            statement.setString(3, icon);
            statement.setString(4, color);
            statement.setInt(5, sortNo);
            statement.setString(6, status);
            return statement;
        }, keys);
        if (changed != 1 || keys.getKey() == null) throw new IllegalStateException("Category insert failed");
        return keys.getKey().longValue();
    }

    @Override
    public int update(long id, String entryType, String name, String icon, String color, int sortNo, String status) {
        return jdbc().update("""
                UPDATE category
                SET entry_type = ?, name = ?, icon = ?, color = ?, sort_no = ?, status = ?
                WHERE ledger_id = 1 AND id = ?
                """, entryType, name, icon, color, sortNo, status, id);
    }

    @Override
    public long referenceCount(long id) {
        Long count = jdbc().queryForObject(
                "SELECT COUNT(*) FROM book_entry WHERE ledger_id = 1 AND category_id = ? AND deleted_at IS NULL", Long.class, id);
        return count == null ? 0 : count;
    }

    @Override
    public int purgeDeletedReferences(long id) {
        return jdbc().update("DELETE FROM book_entry WHERE ledger_id = 1 AND category_id = ? AND deleted_at IS NOT NULL", id);
    }

    @Override
    public int delete(long id) {
        return jdbc().update("DELETE FROM category WHERE ledger_id = 1 AND id = ?", id);
    }

    private static RowMapper<CategoryRow> mapper() {
        return (row, index) -> new CategoryRow(row.getLong("id"), row.getString("entry_type"),
                row.getString("name"), row.getString("icon"), row.getString("color"),
                row.getInt("sort_no"), row.getBoolean("system_default"), row.getString("status"));
    }

    private JdbcTemplate jdbc() {
        var jdbc = jdbcTemplateSupplier.get();
        if (jdbc == null) throw new IllegalStateException("Category persistence requires a datasource");
        return jdbc;
    }
}
