package com.mytallybook.accountbook.auth.store;

import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class JdbcAuthStoreTests {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    private JdbcAuthStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcAuthStore(jdbcTemplate);
    }

    @Test
    void locksTheSingletonConfigurationRowForInitialization() {
        AuthStore.AppConfigState expected = new AuthStore.AppConfigState(false, 10, 3);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class)))
                .thenReturn(expected);

        assertThat(store.lockAppConfig()).isEqualTo(expected);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(RowMapper.class));
        assertThat(normalize(sql.getValue()))
                .contains("FROM app_config")
                .contains("WHERE id = 1")
                .endsWith("FOR UPDATE");
    }

    @Test
    void currentLoginReadLocksByStableUserIdAndPreservesInactiveState() throws Exception {
        var row = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(row.getLong("user_id")).thenReturn(7L);
        when(row.getString("user_status")).thenReturn("DISABLED");
        when(row.getLong("ledger_id")).thenReturn(1L);
        when(row.getLong("member_id")).thenReturn(11L);
        when(row.getString("role")).thenReturn("MEMBER");
        when(row.getString("member_status")).thenReturn("REMOVED");
        when(row.getString("ledger_status")).thenReturn("ACTIVE");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), org.mockito.ArgumentMatchers.eq(7L)))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));
        assertThat(store.lockLoginMembership(7L)).contains(new AuthStore.LoginMembership(
                7L, "DISABLED", 1L, 11L, MemberRole.MEMBER, "REMOVED", "ACTIVE"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), org.mockito.ArgumentMatchers.eq(7L));
        assertThat(normalize(sql.getValue())).contains("WHERE u.id = ?", "lm.ledger_id = 1")
                .doesNotContain("u.openid =", "= 'ACTIVE'").endsWith("FOR UPDATE");
    }

    @Test
    void bindsOnlyTheHmacDigestWhenCreatingASession() throws Exception {
        String digest = "a".repeat(64);
        when(connection.prepareStatement(anyString(),
                org.mockito.ArgumentMatchers.eq(Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(preparedStatement);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(1);
                    keyHolder.getKeyList().add(Map.of("GENERATED_KEY", 91L));
                    return 1;
                });

        long sessionId = store.insertSession(7L, digest, NOW.plusSeconds(3600), NOW);

        ArgumentCaptor<PreparedStatementCreator> creator =
                ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbcTemplate).update(creator.capture(), any(KeyHolder.class));
        creator.getValue().createPreparedStatement(connection);
        verify(preparedStatement).setString(2, digest);
        verify(preparedStatement, never()).setString(2, "raw-token");
        assertThat(sessionId).isEqualTo(91L);
    }

    @Test
    void verifiesSessionAgainstEveryActiveDatabaseStateAndFixedLedger() {
        CurrentUser expected = new CurrentUser(7L, 1L, 11L, MemberRole.OWNER);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(expected));

        assertThat(store.findCurrentUserByTokenHash("b".repeat(64), NOW))
                .contains(expected);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), arguments.capture());
        String normalized = normalize(sql.getValue());
        assertThat(normalized)
                .contains("JOIN app_user")
                .contains("JOIN ledger_member")
                .contains("JOIN ledger")
                .contains("s.revoked_at IS NULL")
                .contains("s.expires_at > ?")
                .contains("u.status = 'ACTIVE'")
                .contains("lm.status = 'ACTIVE'")
                .contains("l.status = 'ACTIVE'")
                .contains("l.id = 1");
        assertThat(arguments.getValue()[0]).isEqualTo("b".repeat(64));
    }

    @Test
    void updatesOnlyPresentProfileFieldsUsingBoundParameters() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        store.updateUserProfile(
                7L,
                true,
                "家庭成员",
                true,
                null,
                NOW
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), arguments.capture());
        assertThat(normalize(sql.getValue()))
                .contains("nickname = CASE WHEN ? THEN ? ELSE nickname END")
                .contains("avatar_url = CASE WHEN ? THEN ? ELSE avatar_url END")
                .doesNotContain("家庭成员");
        assertThat(arguments.getValue())
                .containsExactly(true, "家庭成员", true, null, java.sql.Timestamp.from(NOW), 7L);
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
