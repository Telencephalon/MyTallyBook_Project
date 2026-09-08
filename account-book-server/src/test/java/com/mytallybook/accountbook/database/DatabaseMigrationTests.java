package com.mytallybook.accountbook.database;

import com.mytallybook.accountbook.entry.store.JdbcEntryStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@ResourceLock("account-book-test-schema")
class DatabaseMigrationTests {

    private static MysqlTestDatabaseSupport database;

    @BeforeAll
    static void migrateOriginalV1ThenUpgradeToV2() throws SQLException {
        database = MysqlTestDatabaseSupport.openResetSchema();
        try {
            database.migrateTo("1");
            try (Connection connection = database.openTestDatabaseConnection()) {
                assertTrue(columnExists(connection, "book_entry", "member_id"));
                assertTrue(columnExists(connection, "ledger_invite", "max_uses"));
                assertEquals(
                        "char",
                        columnProperty(connection, "audit_log", "request_id", "data_type")
                );
            }
            database.migrate();
        } catch (RuntimeException | Error failure) {
            database.close();
            throw failure;
        }
    }

    @AfterAll
    static void cleanTestDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void migrationHistoryPreservesV1AndRecordsV2() throws SQLException {
        try (Connection connection = connection()) {
            assertEquals(
                    List.of("1:1", "2:1"),
                    queryStrings(connection, """
                            SELECT CONCAT(version, ':', success)
                            FROM flyway_schema_history
                            WHERE type = 'SQL'
                            ORDER BY installed_rank
                            """)
            );
        }
    }

    @Test
    void migrationCreatesTheApprovedTenTableSchema() throws SQLException {
        try (Connection connection = connection()) {
            assertEquals(10, scalarInt(connection, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name <> 'flyway_schema_history'
                    """));
            assertEquals("utf8mb4", scalarString(connection, """
                    SELECT default_character_set_name
                    FROM information_schema.schemata
                    WHERE schema_name = DATABASE()
                    """));
        }
    }

    @Test
    void ledgerUsesARealDatabaseSingletonConstraint() throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long ownerId = insertUser(connection, "migration-test-owner");
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO ledger (id, name, owner_user_id)
                            VALUES (1, 'Approved Ledger', %d)
                            """.formatted(ownerId));
                }
                assertThrows(SQLException.class, () -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("""
                                INSERT INTO ledger (id, name, owner_user_id)
                                VALUES (2, 'Forbidden Second Ledger', %d)
                                """.formatted(ownerId));
                    }
                });
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void invitationIsSingleUseAndRecordsItsConsumer() throws SQLException {
        try (Connection connection = connection()) {
            assertTrue(columnExists(connection, "ledger_invite", "used_by"));
            assertTrue(columnExists(connection, "ledger_invite", "used_at"));
            assertFalse(columnExists(connection, "ledger_invite", "max_uses"));
            assertFalse(columnExists(connection, "ledger_invite", "used_count"));
            String statusCheckClause = checkClause(
                    connection,
                    "ledger_invite",
                    "ck_invite_status"
            );
            assertTrue(
                    statusCheckClause.contains("USED"),
                    () -> "Actual check clause: " + statusCheckClause
            );

            connection.setAutoCommit(false);
            try {
                long ownerId = insertUser(connection, "migration-invite-owner");
                long consumerId = insertUser(connection, "migration-invite-consumer");
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO ledger (id, name, owner_user_id)
                            VALUES (1, 'Invitation Test Ledger', %d)
                            """.formatted(ownerId));
                }

                assertThrows(SQLException.class, () -> insertUsedInvite(
                        connection,
                        ownerId,
                        null,
                        "CURRENT_TIMESTAMP(3)"
                ));
                assertThrows(SQLException.class, () -> insertUsedInvite(
                        connection,
                        ownerId,
                        consumerId,
                        "NULL"
                ));
                insertUsedInvite(
                        connection,
                        ownerId,
                        consumerId,
                        "CURRENT_TIMESTAMP(3)"
                );
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void entriesUseCreatorAsTheMemberStatisticsDimension() throws SQLException {
        try (Connection connection = connection()) {
            assertFalse(columnExists(connection, "book_entry", "member_id"));
            assertEquals(
                    List.of("ledger_id", "created_by", "entry_date"),
                    indexColumns(connection, "book_entry", "idx_entry_creator_date")
            );
            connection.setAutoCommit(false);
            try {
                long creatorId = insertUser(connection, "migration-entry-creator");
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO ledger (id, name, owner_user_id) VALUES (1, 'Entry Ledger', " + creatorId + ")");
                    statement.executeUpdate("INSERT INTO category (ledger_id, entry_type, name) VALUES (1, 'EXPENSE', 'Entry Category')");
                    statement.executeUpdate("INSERT INTO fund_account (ledger_id, name, account_type) VALUES (1, 'Entry Account', 'CASH')");
                }
                long categoryId = scalarLong(connection, "SELECT id FROM category WHERE ledger_id = 1");
                long accountId = scalarLong(connection, "SELECT id FROM fund_account WHERE ledger_id = 1");
                var store = new JdbcEntryStore(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
                long entryId = store.insert("EXPENSE", new BigDecimal("3.40"), categoryId, accountId,
                        LocalDate.of(2026, 9, 6), "migration entry", "11111111-2222-4333-8444-555555555555", creatorId);
                var row = store.find(entryId).orElseThrow();
                assertEquals(creatorId, row.createdBy());
                assertEquals(new BigDecimal("3.40"), row.amount());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void auditRequestIdSupportsTheHttpContractLength() throws SQLException {
        try (Connection connection = connection()) {
            assertEquals(
                    "varchar",
                    columnProperty(connection, "audit_log", "request_id", "data_type")
            );
            assertEquals(
                    "64",
                    columnProperty(
                            connection,
                            "audit_log",
                            "request_id",
                            "character_maximum_length"
                    )
            );
        }
    }

    private static Connection connection() throws SQLException {
        return database.openTestDatabaseConnection();
    }

    private static boolean columnExists(
            Connection connection,
            String tableName,
            String columnName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) == 1;
            }
        }
    }

    private static String columnProperty(
            Connection connection,
            String tableName,
            String columnName,
            String property
    ) throws SQLException {
        String sql = """
                SELECT %s
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """.formatted(property);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), () -> tableName + "." + columnName + " must exist");
                return resultSet.getString(1).toLowerCase();
            }
        }
    }

    private static String checkClause(
            Connection connection,
            String tableName,
            String constraintName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT check_clause
                FROM information_schema.check_constraints checks
                JOIN information_schema.table_constraints tables
                  ON tables.constraint_schema = checks.constraint_schema
                 AND tables.constraint_name = checks.constraint_name
                WHERE tables.table_schema = DATABASE()
                  AND tables.table_name = ?
                  AND checks.constraint_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), () -> constraintName + " must exist");
                return resultSet.getString(1);
            }
        }
    }

    private static List<String> indexColumns(
            Connection connection,
            String tableName,
            String indexName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                ORDER BY seq_in_index
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            List<String> columns = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
            }
            return columns;
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static long insertUser(Connection connection, String openid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO app_user (openid, nickname) VALUES (?, 'Migration Test User')",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, openid);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void insertUsedInvite(
            Connection connection,
            long ownerId,
            Long usedBy,
            String usedAtExpression
    ) throws SQLException {
        String usedByExpression = usedBy == null ? "NULL" : Long.toString(usedBy);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ledger_invite (
                        ledger_id, token_hash, created_by, used_by, used_at, expires_at, status
                    ) VALUES (
                        1, REPEAT('a', 64), %d, %s, %s,
                        DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY), 'USED'
                    )
                    """.formatted(ownerId, usedByExpression, usedAtExpression));
        }
    }

    private static int scalarInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
