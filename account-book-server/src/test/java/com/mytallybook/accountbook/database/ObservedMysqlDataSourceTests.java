package com.mytallybook.accountbook.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Offline decorator contract tests; JDBC endpoint is a stub, never MySQL evidence. */
class ObservedMysqlDataSourceTests {
    private static final String LOCK = "SELECT initialized, max_users, version FROM app_config WHERE id = 1 FOR UPDATE";
    // No database support/credentials are needed: JdbcTemplate uses the explicitly bound decorated connection.
    private final ObservedMysqlDataSource dataSource = new ObservedMysqlDataSource(null);
    private final Endpoint endpoint = new Endpoint();
    private final Connection connection = dataSource.connection(endpoint.connection());
    private final List<ObservedMysqlDataSource.Event> events = new ArrayList<>();

    @AfterEach void clearTestTransactionBinding() {
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
        TransactionSynchronizationManager.clear();
    }

    // Catches missing plain-Statement interception on the real no-parameter JdbcTemplate overload.
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void jdbcTemplateConfigQueryDelegatesMapsAndObservesBoundConnection(boolean prepared) {
        bind();
        RowMapper<List<Object>> mapper = (rows, index) -> {
            endpoint.trace.add("MAP");
            return List.of(rows.getBoolean("initialized"), rows.getInt("max_users"), rows.getLong("version"));
        };
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Object> result = prepared ? jdbc.queryForObject(LOCK, mapper, new Object[0])
                : jdbc.queryForObject(LOCK, mapper);
        assertThat(result).containsExactly(true, 10, 7L);
        assertThat(endpoint.executedSql).containsExactly(LOCK);
        assertThat(endpoint.trace).containsSubsequence("BEFORE", "EXECUTE", "AFTER", "MAP", "ROWS_CLOSE", "ROWS_CLOSED", "STATEMENT_CLOSE");
        assertThat(events).extracting(ObservedMysqlDataSource.Event::phase)
                .containsExactly(ObservedMysqlDataSource.Phase.BEFORE, ObservedMysqlDataSource.Phase.AFTER, ObservedMysqlDataSource.Phase.ROWS_CLOSED);
        assertThat(events).allSatisfy(event -> assertThat(event.connection()).isSameAs(connection));
        assertThat(dataSource.verifiedWriteConnections.get()).isEqualTo(1);
        assertThat(endpoint.queryTimeout).isEqualTo(12);
        assertThat(endpoint.connectionClosed).isFalse(); // JdbcTemplate must retain the bound transaction connection.
    }

    // Catches exception swallowing/wrapping or fabricated AFTER success for failed driver execution.
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void jdbcTemplateFailureRetainsDriverCauseAndHasNoAfterSuccess(boolean prepared) {
        bind();
        SQLException failure = new SQLException("offline endpoint failure", "42000");
        endpoint.failure = failure;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        RowMapper<Integer> mapper = (rows, index) -> rows.getInt("max_users");
        assertThatThrownBy(() -> {
            if (prepared) jdbc.queryForObject(LOCK, mapper, new Object[0]);
            else jdbc.queryForObject(LOCK, mapper);
        }).isInstanceOf(DataAccessException.class).hasRootCause(failure);
        assertThat(endpoint.executedSql).containsExactly(LOCK);
        assertThat(events).extracting(ObservedMysqlDataSource.Event::phase).containsExactly(ObservedMysqlDataSource.Phase.BEFORE);
        assertThat(endpoint.trace).contains("STATEMENT_CLOSE").doesNotContain("AFTER", "MAP");
    }

    // Catches validating only a separate inspection connection instead of the executing bound one.
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configQueryRejectsMissingTransactionBeforeDriverExecution(boolean prepared) {
        bind();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> queryDirectly(prepared)).isInstanceOf(AssertionError.class);
        assertThat(endpoint.executedSql).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configQueryRejectsWrongIsolationBeforeDriverExecution(boolean prepared) {
        bind();
        endpoint.isolation = Connection.TRANSACTION_REPEATABLE_READ;
        assertThatThrownBy(() -> queryDirectly(prepared)).isInstanceOf(AssertionError.class);
        assertThat(endpoint.executedSql).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void configQueryRejectsDifferentBoundConnectionBeforeDriverExecution(boolean prepared) {
        bind();
        TransactionSynchronizationManager.unbindResource(dataSource);
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(endpoint.connection()));
        assertThatThrownBy(() -> queryDirectly(prepared)).isInstanceOf(AssertionError.class);
        assertThat(endpoint.executedSql).isEmpty();
    }

    // Protects execute/executeUpdate delegation for both statement kinds, not just executeQuery.
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void updateAndExecuteKeepActualReturnValuesAndObservationOrder(boolean prepared) throws SQLException {
        bind();
        String sql = "UPDATE example SET value = 1";
        if (prepared) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                assertThat(statement.executeUpdate()).isEqualTo(3);
                assertThat(statement.execute()).isFalse();
            }
        } else {
            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate(sql)).isEqualTo(3);
                assertThat(statement.execute(sql)).isFalse();
            }
        }
        assertThat(endpoint.executedSql).containsExactly(sql, sql);
        assertThat(endpoint.trace).containsSubsequence("BEFORE", "EXECUTE", "AFTER", "BEFORE", "EXECUTE", "AFTER", "STATEMENT_CLOSE");
        assertThat(events).hasSize(4);
    }

    private void queryDirectly(boolean prepared) throws SQLException {
        if (prepared) {
            try (PreparedStatement statement = connection.prepareStatement(LOCK); ResultSet ignored = statement.executeQuery()) { }
        } else {
            try (Statement statement = connection.createStatement(); ResultSet ignored = statement.executeQuery(LOCK)) { }
        }
    }

    private void bind() {
        TransactionSynchronizationManager.bindResource(dataSource, new ConnectionHolder(connection));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        dataSource.observer = event -> {
            events.add(event); endpoint.trace.add(event.phase().name());
        };
    }

    /** Minimal JDBC endpoint behind the real decorator and real JdbcTemplate; no duplicate wrapping logic. */
    private static final class Endpoint {
        final List<String> trace = new ArrayList<>(), executedSql = new ArrayList<>();
        int isolation = Connection.TRANSACTION_READ_COMMITTED, queryTimeout;
        boolean connectionClosed;
        SQLException failure;

        Connection connection() {
            return proxy(Connection.class, (object, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement(null);
                case "prepareStatement" -> statement((String) args[0]);
                case "getAutoCommit" -> false;
                case "getTransactionIsolation" -> isolation;
                case "isClosed" -> connectionClosed;
                case "close" -> { connectionClosed = true; yield null; }
                case "equals" -> object == args[0];
                case "hashCode" -> System.identityHashCode(object);
                case "toString" -> "offline JDBC endpoint";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        Statement statement(String preparedSql) {
            return proxy(preparedSql == null ? Statement.class : PreparedStatement.class, (object, method, args) -> {
                String name = method.getName();
                if (name.startsWith("execute")) {
                    executedSql.add(preparedSql == null ? (String) args[0] : preparedSql);
                    trace.add("EXECUTE");
                    if (failure != null) throw failure;
                    return switch (name) {
                        case "executeQuery" -> rows();
                        case "executeUpdate" -> 3;
                        case "execute" -> false;
                        default -> throw new UnsupportedOperationException(name);
                    };
                }
                return switch (name) {
                    case "setQueryTimeout" -> { queryTimeout = (Integer) args[0]; yield null; }
                    case "getQueryTimeout" -> queryTimeout;
                    case "getWarnings" -> null;
                    case "close" -> { trace.add("STATEMENT_CLOSE"); yield null; }
                    default -> throw new UnsupportedOperationException(name);
                };
            });
        }

        ResultSet rows() {
            int[] cursor = {0};
            return proxy(ResultSet.class, (object, method, args) -> switch (method.getName()) {
                case "next" -> ++cursor[0] == 1;
                case "getBoolean" -> true;
                case "getInt" -> 10;
                case "getLong" -> 7L;
                case "close" -> { trace.add("ROWS_CLOSE"); yield null; }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        @SuppressWarnings("unchecked")
        private static <T> T proxy(Class<? extends T> type, java.lang.reflect.InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }
    }
}
