package com.mytallybook.accountbook.member.store;

import com.mytallybook.accountbook.security.MemberRole;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class JdbcMemberStoreTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcMemberStore store = new JdbcMemberStore(jdbc);

    @Test void locksFixedLedgerAndMapsItsCurrentState() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(1L);
        when(row.getLong("owner_user_id")).thenReturn(7L);
        when(row.getInt("max_members")).thenReturn(8);
        when(row.getString("status")).thenReturn("ACTIVE");
        when(row.getLong("version")).thenReturn(3L);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));
        assertThat(store.lockLedger()).contains(new MemberStore.LedgerState(1, 7, 8, "ACTIVE", 3));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class));
        assertThat(normalize(sql.getValue())).contains("FROM ledger", "WHERE id = 1").endsWith("FOR UPDATE");
    }

    @Test void locksAllMembershipStatesInIdOrderAndMapsJoinedUserStatus() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("member_id")).thenReturn(11L);
        when(row.getLong("user_id")).thenReturn(7L);
        when(row.getString("user_status")).thenReturn("DISABLED");
        when(row.getString("role")).thenReturn("MEMBER");
        when(row.getString("member_status")).thenReturn("REMOVED");
        when(row.getString("nickname")).thenReturn("name");
        when(row.getString("display_name")).thenReturn("alias");
        when(row.getTimestamp("joined_at")).thenReturn(Timestamp.from(Instant.EPOCH));
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));
        assertThat(store.lockMembers()).containsExactly(new MemberStore.MemberState(11, 7, "DISABLED", MemberRole.MEMBER, "REMOVED", "name", "alias", Instant.EPOCH));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class));
        assertThat(normalize(sql.getValue())).contains("JOIN app_user", "lm.ledger_id = 1", "ORDER BY lm.id")
                .doesNotContain("= 'ACTIVE'").endsWith("FOR UPDATE");
    }

    @Test void userLookupBindsOpenidAndRetainsUnavailableState() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(7L);
        when(row.getString("status")).thenReturn("DELETED");
        when(jdbc.query(anyString(), any(RowMapper.class), eq("member-openid"))).thenAnswer(call ->
                List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));
        assertThat(store.lockUserByOpenid("member-openid")).contains(new MemberStore.UserState(7, "DELETED"));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("member-openid"));
        assertThat(normalize(sql.getValue())).contains("WHERE openid = ?").doesNotContain("member-openid").endsWith("FOR UPDATE");
    }

    @Test void ordinaryReadsDoNotAcquireWriteLocksOrMutateData() {
        assertThat(store.readLedger()).isEmpty();
        assertThat(store.readMembers()).isEmpty();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class));
        assertThat(sql.getAllValues()).allSatisfy(value -> assertThat(value).doesNotContain("FOR UPDATE"));
        verifyNoMoreInteractions(jdbc);
    }

    private static String normalize(String sql) { return sql.replaceAll("\\s+", " ").trim(); }
    @Test void roleChangeBindsExpectedActiveRoleAndNeverTouchesAlias() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThat(store.changeRole(12, MemberRole.MEMBER, MemberRole.ADMIN)).isEqualTo(1);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(normalize(sql.getValue())).contains("SET role = ?", "ledger_id = 1 AND id = ? AND role = ? AND status = 'ACTIVE'")
                .doesNotContain("display_name", "nickname", "SET status");
        assertThat(args.getValue()).containsExactly("ADMIN", 12L, "MEMBER");
    }

    @Test void removalBindsStatusTimeAndExpectedRoleWithoutDeletingHistory() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertThat(store.remove(13, MemberRole.ADMIN, "LEFT", Instant.EPOCH)).isEqualTo(1);
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(normalize(sql.getValue())).contains("SET status = ?, removed_at = ?", "ledger_id = 1 AND id = ? AND role = ? AND status = 'ACTIVE'")
                .doesNotContain("DELETE", "display_name", "app_user");
        assertThat(args.getValue()).containsExactly("LEFT", Timestamp.from(Instant.EPOCH), 13L, "ADMIN");
    }

    @Test void transferBindsExpectedOwnerAndVersionAndReturnsLostRaceCount() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThat(store.transferOwner(1, 2, 4)).isZero();
        var sql = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(normalize(sql.getValue())).contains("owner_user_id = ?, version = version + 1",
                "WHERE id = 1 AND owner_user_id = ? AND version = ? AND status = 'ACTIVE'");
        assertThat(args.getValue()).containsExactly(2L, 1L, 4L);
    }
    @Test void reactivationBindsExistingIdentityAndPreservesAliasAndNickname() {
        Instant now=Instant.parse("2026-09-05T00:00:00Z");
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertThat(store.reactivateMember(11,7,"REMOVED",now)).isEqualTo(1);
        var sql=ArgumentCaptor.forClass(String.class); var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(),args.capture());
        assertThat(normalize(sql.getValue())).contains("role = 'MEMBER'", "status = 'ACTIVE'", "removed_at = NULL",
                "ledger_id = 1 AND id = ? AND user_id = ? AND status = ?", "status IN ('REMOVED','LEFT')")
                .doesNotContain("display_name =", "nickname =", "INSERT");
        assertThat(args.getValue()).containsExactly(java.sql.Timestamp.from(now),11L,7L,"REMOVED");
    }
    @Test void newMemberAlwaysUsesFixedLedgerAndMemberRole() throws Exception {
        when(jdbc.update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),any(org.springframework.jdbc.support.KeyHolder.class)))
                .thenAnswer(call->{((org.springframework.jdbc.support.KeyHolder)call.getArgument(1)).getKeyList().add(java.util.Map.of("id",12L)); return 1;});
        assertThat(store.insertMember(7,Instant.EPOCH)).isEqualTo(12);
        var creator=ArgumentCaptor.forClass(org.springframework.jdbc.core.PreparedStatementCreator.class);
        verify(jdbc).update(creator.capture(),any(org.springframework.jdbc.support.KeyHolder.class));
        var connection=mock(java.sql.Connection.class); var statement=mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString(),eq(java.sql.Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);
        var sql=ArgumentCaptor.forClass(String.class); verify(connection).prepareStatement(sql.capture(),eq(java.sql.Statement.RETURN_GENERATED_KEYS));
        assertThat(normalize(sql.getValue())).contains("VALUES (1, ?, 'MEMBER', 'ACTIVE', ?)");
        verify(statement).setLong(1,7); verify(statement).setTimestamp(2,java.sql.Timestamp.from(Instant.EPOCH));
    }
}
