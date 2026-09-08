package com.mytallybook.accountbook.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
@ResourceLock("account-book-test-schema")
class DatabaseMigrationGuardTests {

    private static MysqlTestDatabaseSupport database;

    @BeforeEach
    void migrateOnlyLegacyV1() throws SQLException {
        database = MysqlTestDatabaseSupport.openResetSchema();
        try {
            database.migrateTo("1");
        } catch (RuntimeException | Error failure) {
            database.close();
            throw failure;
        }
    }

    @AfterEach
    void cleanTestDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void v2RejectsMultipleLegacyLedgersBeforeChangingPersistentSchema()
            throws SQLException {
        try (Connection connection = database.openTestDatabaseConnection()) {
            long ownerId = insertUser(connection);
            insertLedger(connection, ownerId);
            insertLedger(connection, 2, "Second Legacy Ledger", ownerId);

            RuntimeException failure = assertThrows(RuntimeException.class, database::migrate);
            assertGuardRejectedWithoutPersistentV2Changes(connection, failure);
        }
    }

    @Test
    void v2RejectsTheOnlyLegacyLedgerWhenItsIdIsNotOneBeforeChangingPersistentSchema()
            throws SQLException {
        try (Connection connection = database.openTestDatabaseConnection()) {
            long ownerId = insertUser(connection);
            insertLedger(connection, 2, "Only Nonstandard Legacy Ledger", ownerId);

            RuntimeException failure = assertThrows(RuntimeException.class, database::migrate);
            assertGuardRejectedWithoutPersistentV2Changes(connection, failure);
        }
    }

    @Test
    void v2RejectsConsumedOrUnmappableLegacyInvitesBeforeChangingPersistentSchema()
            throws SQLException {
        try (Connection connection = database.openTestDatabaseConnection()) {
            long ownerId = insertUser(connection);
            insertLedger(connection, ownerId);
            insertConsumedLegacyInvite(connection, ownerId);

            RuntimeException failure = assertThrows(RuntimeException.class, database::migrate);
            assertGuardRejectedWithoutPersistentV2Changes(connection, failure);
        }
    }

    private static long insertUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO app_user (openid, nickname) VALUES "
                        + "('migration-guard-owner', 'Migration Guard Owner')",
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void insertLedger(Connection connection, long ownerId) throws SQLException {
        insertLedger(connection, 1, "Migration Guard Ledger", ownerId);
    }

    private static void insertLedger(
            Connection connection,
            long ledgerId,
            String ledgerName,
            long ownerId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger (id, name, owner_user_id)
                VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, ledgerId);
            statement.setString(2, ledgerName);
            statement.setLong(3, ownerId);
            statement.executeUpdate();
        }
    }

    private static void insertConsumedLegacyInvite(Connection connection, long ownerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_invite (
                    ledger_id, token_hash, created_by, max_uses, used_count, expires_at, status
                ) VALUES (
                    1, REPEAT('b', 64), ?, 1, 1,
                    DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY), 'EXHAUSTED'
                )
                """)) {
            statement.setLong(1, ownerId);
            statement.executeUpdate();
        }
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

    private static void assertGuardRejectedWithoutPersistentV2Changes(
            Connection connection,
            RuntimeException failure
    ) throws SQLException {
        assertTrue(
                containsMessage(failure, "ck_v2_legacy_state_is_safe"),
                () -> "Unexpected migration failure: " + failure
        );
        assertFalse(columnExists(connection, "ledger", "singleton_key"));
        assertFalse(columnExists(connection, "ledger_invite", "used_by"));
        assertFalse(columnExists(connection, "ledger_invite", "used_at"));
        assertTrue(columnExists(connection, "ledger_invite", "max_uses"));
        assertTrue(columnExists(connection, "ledger_invite", "used_count"));
        assertTrue(columnExists(connection, "book_entry", "member_id"));
        assertEquals("char", columnProperty(connection, "audit_log", "request_id", "data_type"));
        assertEquals(
                "36",
                columnProperty(connection, "audit_log", "request_id", "character_maximum_length")
        );
    }

    private static String columnProperty(
            Connection connection,
            String tableName,
            String columnName,
            String propertyName
    ) throws SQLException {
        String sql = """
                SELECT %s
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name = ?
                """.formatted(propertyName);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static boolean containsMessage(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
