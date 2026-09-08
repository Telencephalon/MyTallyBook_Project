package com.mytallybook.accountbook.database;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MysqlTestDatabaseSupportTests {

    private static final String TEST_URL = "jdbc:mysql://127.0.0.1:13306/account_book_test"
            + "?useUnicode=true&characterEncoding=UTF-8";

    @Test
    void acceptsOnlyTheExactTestDatabaseName() {
        assertThatCode(() -> MysqlTestDatabaseSupport.validateDatabaseName("account_book_test"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> MysqlTestDatabaseSupport.validateDatabaseName("account_book"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MysqlTestDatabaseSupport.validateDatabaseName(
                "account_book_test_extra"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAUrlThatDoesNotSelectTheExactTestDatabaseBeforeOpeningAConnection() {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment("jdbc:mysql://127.0.0.1:13306/account_book"),
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(connectionFactory, migrationFactory);
    }

    @Test
    void rejectsAConnectionThatDoesNotUseTheLoopbackSshTunnel() {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment("jdbc:mysql://117.72.101.42:3306/account_book_test"),
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1");

        verifyNoInteractions(connectionFactory, migrationFactory);
    }

    @Test
    void rejectsAConnectionThatDoesNotUseTheDedicatedTunnelPort() {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment("jdbc:mysql://127.0.0.1:3306/account_book_test"),
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("13306");

        verifyNoInteractions(connectionFactory, migrationFactory);
    }

    @Test
    void rejectsMissingOrIncorrectResetConfirmationBeforeOpeningAConnection() {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();
        Map<String, String> missingConfirmation = Map.of(
                "DB_TEST_URL", TEST_URL,
                "DB_TEST_USERNAME", "account_book_test_app",
                "DB_TEST_PASSWORD", "test_password"
        );
        Map<String, String> wrongConfirmation = environment(TEST_URL);
        wrongConfirmation.put("DB_TEST_RESET_ALLOWED", "account_book_dev");

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                missingConfirmation,
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                wrongConfirmation,
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(connectionFactory, migrationFactory);
    }

    @Test
    void rejectsAnyAccountOtherThanTheDedicatedTestAccount() {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();
        Map<String, String> environment = environment(TEST_URL);
        environment.put("DB_TEST_USERNAME", "root");

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment,
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("account_book_test_app");

        verifyNoInteractions(connectionFactory, migrationFactory);
    }

    @Test
    void validatesTheServerSelectedDatabaseAndExactTestAccountBeforeCleaningSchemaObjects()
            throws Exception {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperations migrationOperations = mock();
        Connection connection = mock();
        Statement statement = mock();
        ResultSet resultSet = mock();

        when(connectionFactory.open(TEST_URL, "account_book_test_app", "test_password"))
                .thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("account_book_test");
        when(resultSet.getString(2)).thenReturn("account_book_test_app@127.0.0.1");
        when(migrationFactory.create(TEST_URL, "account_book_test_app", "test_password"))
                .thenReturn(migrationOperations);

        MysqlTestDatabaseSupport support = MysqlTestDatabaseSupport.openResetSchema(
                environment(TEST_URL),
                connectionFactory,
                migrationFactory
        );
        support.close();
        support.close();

        verify(statement).executeQuery("SELECT DATABASE(), CURRENT_USER()");
        verify(migrationOperations, times(2)).clean();
        verify(migrationFactory).create(TEST_URL, "account_book_test_app", "test_password");
    }

    @Test
    void refusesToCleanWhenTheServerReportsAnotherSelectedDatabase() throws Exception {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();
        Connection connection = mock();
        Statement statement = mock();
        ResultSet resultSet = mock();

        when(connectionFactory.open(TEST_URL, "account_book_test_app", "test_password"))
                .thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("account_book_dev");
        when(resultSet.getString(2)).thenReturn("account_book_test_app@127.0.0.1");

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment(TEST_URL),
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account_book_dev");

        verifyNoInteractions(migrationFactory);
    }

    @Test
    void refusesToCleanWhenTheServerReportsAnotherAccountBeforeCreatingMigrationOperations()
            throws Exception {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();
        Connection connection = mock();
        Statement statement = mock();
        ResultSet resultSet = mock();

        when(connectionFactory.open(TEST_URL, "account_book_test_app", "test_password"))
                .thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("account_book_test");
        when(resultSet.getString(2)).thenReturn("another_test_account@127.0.0.1");

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment(TEST_URL),
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another_test_account@127.0.0.1");

        verifyNoInteractions(migrationFactory);
    }

    @Test
    void refusesToCleanWhenTheServerReportsTheTestAccountFromAnotherHostBeforeCreatingMigrationOperations()
            throws Exception {
        MysqlTestDatabaseSupport.SqlConnectionFactory connectionFactory = mock();
        MysqlTestDatabaseSupport.MigrationOperationsFactory migrationFactory = mock();
        Connection connection = mock();
        Statement statement = mock();
        ResultSet resultSet = mock();

        when(connectionFactory.open(TEST_URL, "account_book_test_app", "test_password"))
                .thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT DATABASE(), CURRENT_USER()")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("account_book_test");
        when(resultSet.getString(2)).thenReturn("account_book_test_app@%");

        assertThatThrownBy(() -> MysqlTestDatabaseSupport.openResetSchema(
                environment(TEST_URL),
                connectionFactory,
                migrationFactory
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account_book_test_app@%");

        verifyNoInteractions(migrationFactory);
    }

    private static Map<String, String> environment(String testUrl) {
        return new HashMap<>(Map.of(
                "DB_TEST_URL", testUrl,
                "DB_TEST_USERNAME", "account_book_test_app",
                "DB_TEST_PASSWORD", "test_password",
                "DB_TEST_RESET_ALLOWED", "account_book_test"
        ));
    }
}
