package com.mytallybook.accountbook.database;

import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Test-only observation, never supplies SQL results or replaces a transaction/service. */
final class ObservedMysqlDataSource extends AbstractDataSource {
    enum Phase { BEFORE, AFTER, ROWS_CLOSED }
    record Event(String sql, Map<Integer, Object> parameters, Phase phase, Connection connection) {}
    @FunctionalInterface interface Observer { void observe(Event event) throws Exception; }
    static final Observer NONE = event -> {};
    volatile Observer observer = NONE;
    volatile int isolation = Connection.TRANSACTION_READ_COMMITTED;
    final AtomicInteger verifiedWriteConnections = new AtomicInteger();
    private final MysqlTestDatabaseSupport database;

    ObservedMysqlDataSource(MysqlTestDatabaseSupport database) { this.database = database; }

    @Override public Connection getConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", database.username());
        properties.setProperty("password", database.password());
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
        properties.setProperty("useCursorFetch", "false");
        Connection real = DriverManager.getConnection(database.jdbcUrl(), properties);
        try {
            real.setTransactionIsolation(isolation);
            assertThat(real.getTransactionIsolation()).isEqualTo(isolation);
            try (Statement statement = real.createStatement()) {
                statement.setQueryTimeout(10);
                statement.execute("SET SESSION innodb_lock_wait_timeout = 8");
                statement.execute("SET SESSION lock_wait_timeout = 8");
            }
            return connection(real);
        } catch (Throwable failure) {
            try { real.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    @Override public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Only the guarded test identity is allowed");
    }

    // Package-private so offline JdbcTemplate tests exercise this exact decorator, not a duplicate.
    Connection connection(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "Observed test connection";
                        };
                    }
                    Object result = invoke(real, method, args);
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        return statement((PreparedStatement) result, (Connection) proxy, sql);
                    }
                    if (method.getName().equals("createStatement")) {
                        return statement((Statement) result, (Connection) proxy, null);
                    }
                    return result;
                });
    }

    private Statement statement(Statement real, Connection connection, String preparedSql) throws SQLException {
        real.setQueryTimeout(12);
        Map<Integer, Object> parameters = new HashMap<>();
        Class<?> statementType = preparedSql == null ? Statement.class : PreparedStatement.class;
        return (Statement) Proxy.newProxyInstance(statementType.getClassLoader(),
                new Class<?>[]{statementType}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set") && args != null && args.length >= 2
                            && args[0] instanceof Integer index) {
                        parameters.put(index, method.getName().equals("setNull") ? null : args[1]);
                    }
                    String rawSql = args != null && args.length > 0 && args[0] instanceof String suppliedSql
                            ? suppliedSql : preparedSql;
                    String sql = rawSql == null ? null
                            : rawSql.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
                    boolean execute = method.getName().startsWith("execute") && sql != null;
                    if (execute) {
                        if (isConfigLock(sql)) verifyBusinessConnection(connection);
                        fire(sql, parameters, Phase.BEFORE, connection);
                    }
                    Object result = invoke(real, method, args);
                    if (execute) fire(sql, parameters, Phase.AFTER, connection);
                    if (result instanceof ResultSet rows && method.getName().equals("executeQuery")) {
                        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                                (rowProxy, rowMethod, rowArgs) -> {
                                    Object value = invoke(rows, rowMethod, rowArgs);
                                    if (rowMethod.getName().equals("close")) fire(sql, parameters, Phase.ROWS_CLOSED, connection);
                                    return value;
                                });
                    }
                    return result;
                });
    }

    private void verifyBusinessConnection(Connection connection) throws SQLException {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(connection.getAutoCommit()).isFalse();
        assertThat(connection.getTransactionIsolation()).isEqualTo(isolation);
        Object holder = TransactionSynchronizationManager.getResource(this);
        assertThat(holder).isInstanceOf(ConnectionHolder.class);
        assertThat(((ConnectionHolder) holder).getConnection()).isSameAs(connection);
        verifiedWriteConnections.incrementAndGet();
    }

    private void fire(String sql, Map<Integer, Object> parameters, Phase phase, Connection connection) throws Exception {
        observer.observe(new Event(sql, new HashMap<>(parameters), phase, connection));
    }

    static boolean isConfigLock(String sql) {
        return sql.startsWith("select initialized, max_users, version from app_config") && sql.endsWith("for update");
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
