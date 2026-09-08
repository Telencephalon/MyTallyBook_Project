package com.mytallybook.accountbook.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;

final class MysqlTestDatabaseSupport implements AutoCloseable {

    private static final MysqlSchemaCleanupSafety CLEANUP_SAFETY = new MysqlSchemaCleanupSafety();

    static void forbidFurtherCleanup() {
        CLEANUP_SAFETY.forbidCleanup();
    }

    static final String TEST_DATABASE = "account_book_test";
    static final String TEST_USERNAME = "account_book_test_app";
    static final int TEST_TUNNEL_PORT = 13306;
    static final String RESET_CONFIRMATION_VARIABLE = "DB_TEST_RESET_ALLOWED";
    static final String TEST_CURRENT_USER = "account_book_test_app@127.0.0.1";
    static final String SELECT_SERVER_IDENTITY_SQL = "SELECT DATABASE(), CURRENT_USER()";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final SqlConnectionFactory connectionFactory;
    private final MigrationOperations migrationOperations;
    private boolean closed;

    private MysqlTestDatabaseSupport(
            String jdbcUrl,
            String username,
            String password,
            SqlConnectionFactory connectionFactory,
            MigrationOperations migrationOperations
    ) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.connectionFactory = connectionFactory;
        this.migrationOperations = migrationOperations;
    }

    static MysqlTestDatabaseSupport openResetSchema() throws SQLException {
        return openResetSchema(
                System.getenv(),
                DriverManager::getConnection,
                FlywayMigrationOperations::new
        );
    }

    static MysqlTestDatabaseSupport openResetSchema(
            Map<String, String> environment,
            SqlConnectionFactory connectionFactory,
            MigrationOperationsFactory migrationFactory
    ) throws SQLException {
        CLEANUP_SAFETY.requireAllowed();
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(connectionFactory, "connectionFactory must not be null");
        Objects.requireNonNull(migrationFactory, "migrationFactory must not be null");

        String jdbcUrl = required(environment, "DB_TEST_URL");
        String username = required(environment, "DB_TEST_USERNAME");
        String password = required(environment, "DB_TEST_PASSWORD");
        String resetConfirmation = required(environment, RESET_CONFIRMATION_VARIABLE);

        URI databaseUri = mysqlUri(jdbcUrl);
        validateDatabaseName(databaseNameFrom(databaseUri));
        validateLoopback(databaseUri);
        validateTunnelPort(databaseUri);
        validateUsername(username);
        validateResetConfirmation(resetConfirmation);
        verifyServerIdentity(jdbcUrl, username, password, connectionFactory);

        MigrationOperations migrationOperations = migrationFactory.create(
                jdbcUrl,
                username,
                password
        );
        CLEANUP_SAFETY.requireAllowed();
        migrationOperations.clean();
        return new MysqlTestDatabaseSupport(
                jdbcUrl,
                username,
                password,
                connectionFactory,
                migrationOperations
        );
    }

    static void validateDatabaseName(String databaseName) {
        if (!TEST_DATABASE.equals(databaseName)) {
            throw new IllegalArgumentException(
                    "MySQL integration tests may use only " + TEST_DATABASE
            );
        }
    }

    void migrateTo(String version) {
        migrationOperations.migrateTo(version);
    }

    void migrate() {
        migrationOperations.migrate();
    }

    Connection openTestDatabaseConnection() throws SQLException {
        return connectionFactory.open(jdbcUrl, username, password);
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        CLEANUP_SAFETY.requireAllowed();
        migrationOperations.clean();
        closed = true;
    }

    private static void verifyServerIdentity(
            String jdbcUrl,
            String username,
            String password,
            SqlConnectionFactory connectionFactory
    ) throws SQLException {
        try (Connection connection = connectionFactory.open(jdbcUrl, username, password);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(SELECT_SERVER_IDENTITY_SQL)) {
            if (!resultSet.next()) {
                throw new SQLException("Server identity query returned no result");
            }
            String selectedDatabase = resultSet.getString(1);
            if (!TEST_DATABASE.equals(selectedDatabase)) {
                throw new IllegalStateException(
                        "Database server selected " + selectedDatabase
                                + "; refusing to clean anything except " + TEST_DATABASE
                );
            }
            String currentUser = resultSet.getString(2);
            if (!TEST_CURRENT_USER.equals(currentUser)) {
                throw new IllegalStateException(
                        "Database server authenticated as " + currentUser
                                + "; refusing to clean unless connected as " + TEST_CURRENT_USER
                );
            }
        }
    }

    private static URI mysqlUri(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("DB_TEST_URL must be a MySQL JDBC URL");
        }
        try {
            return URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("DB_TEST_URL is not a valid JDBC URL", exception);
        }
    }

    private static String databaseNameFrom(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.length() <= 1 || path.indexOf('/', 1) >= 0) {
            throw new IllegalArgumentException("DB_TEST_URL must select one database");
        }
        return path.substring(1);
    }

    private static void validateLoopback(URI uri) {
        if (!"127.0.0.1".equals(uri.getHost())) {
            throw new IllegalArgumentException(
                    "DB_TEST_URL must use the 127.0.0.1 SSH tunnel"
            );
        }
    }

    private static void validateTunnelPort(URI uri) {
        if (uri.getPort() != TEST_TUNNEL_PORT) {
            throw new IllegalArgumentException(
                    "DB_TEST_URL must use the dedicated SSH tunnel port " + TEST_TUNNEL_PORT
            );
        }
    }

    private static void validateUsername(String username) {
        if (!TEST_USERNAME.equals(username)) {
            throw new IllegalArgumentException(
                    "DB_TEST_USERNAME must be the dedicated " + TEST_USERNAME + " account"
            );
        }
    }

    private static void validateResetConfirmation(String resetConfirmation) {
        if (!TEST_DATABASE.equals(resetConfirmation)) {
            throw new IllegalArgumentException(
                    RESET_CONFIRMATION_VARIABLE + " must equal " + TEST_DATABASE
            );
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be configured for MySQL integration tests"
            );
        }
        return value;
    }

    interface MigrationOperations {
        void clean();

        void migrate();

        void migrateTo(String version);
    }

    @FunctionalInterface
    interface MigrationOperationsFactory {
        MigrationOperations create(String jdbcUrl, String username, String password);
    }

    @FunctionalInterface
    interface SqlConnectionFactory {
        Connection open(String jdbcUrl, String username, String password) throws SQLException;
    }

    private static final class FlywayMigrationOperations implements MigrationOperations {

        private final String jdbcUrl;
        private final String username;
        private final String password;

        private FlywayMigrationOperations(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public void clean() {
            configuration().load().clean();
        }

        @Override
        public void migrate() {
            configuration().load().migrate();
        }

        @Override
        public void migrateTo(String version) {
            configuration()
                    .target(MigrationVersion.fromVersion(version))
                    .load()
                    .migrate();
        }

        private FluentConfiguration configuration() {
            return Flyway.configure()
                    .dataSource(jdbcUrl, username, password)
                    .cleanDisabled(false);
        }
    }
}
