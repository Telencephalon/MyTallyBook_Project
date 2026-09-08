package com.mytallybook.accountbook.invite.store;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SuppressWarnings("unchecked")
class JdbcInviteStoreTests {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final JdbcInviteStore store=new JdbcInviteStore(jdbc);
    final Instant now=Instant.parse("2026-09-05T00:00:00Z");
    @Test void insertsOnlyBoundDigestAndFixedLedgerWithGeneratedSafeIdentity() throws Exception {
        when(jdbc.update(any(PreparedStatementCreator.class),any(KeyHolder.class))).thenAnswer(call->{
            ((KeyHolder)call.getArgument(1)).getKeyList().add(Map.of("id",51L)); return 1;
        });
        assertThat(store.insert("a".repeat(64),7,now,now.plusSeconds(60))).isEqualTo(51);
        var creator=ArgumentCaptor.forClass(PreparedStatementCreator.class);
        verify(jdbc).update(creator.capture(),any(KeyHolder.class));
        Connection connection=mock(Connection.class); PreparedStatement statement=mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString(),eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);
        var sql=ArgumentCaptor.forClass(String.class); verify(connection).prepareStatement(sql.capture(),eq(Statement.RETURN_GENERATED_KEYS));
        assertThat(normalize(sql.getValue())).contains("token_hash", "VALUES (1, ?, ?, ?, ?, 'ACTIVE')").doesNotContain("a".repeat(64));
        verify(statement).setString(1,"a".repeat(64)); verify(statement).setLong(2,7);
        verify(statement).setTimestamp(3,Timestamp.from(now)); verify(statement).setTimestamp(4,Timestamp.from(now.plusSeconds(60)));
    }
    @Test void locksByHashWithBoundValueAndMapsNoDigestOrRawField() throws Exception {
        var row=mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(51L); when(row.getLong("ledger_id")).thenReturn(1L); when(row.getLong("created_by")).thenReturn(7L);
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(now.minusSeconds(60)));
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(now)); when(row.getString("status")).thenReturn("USED");
        when(row.getObject("used_by",Long.class)).thenReturn(8L); when(row.getTimestamp("used_at")).thenReturn(Timestamp.from(now.minusSeconds(1)));
        when(jdbc.query(anyString(),any(RowMapper.class),eq("bound-hash"))).thenAnswer(call->List.of(((RowMapper<?>)call.getArgument(1)).mapRow(row,0)));
        assertThat(store.lockByHash("bound-hash")).contains(new InviteStore.InviteRow(51,1,7,now.minusSeconds(60),now,"USED",8L,now.minusSeconds(1)));
        var sql=ArgumentCaptor.forClass(String.class); verify(jdbc).query(sql.capture(),any(RowMapper.class),eq("bound-hash"));
        assertThat(normalize(sql.getValue())).contains("ledger_id = 1", "token_hash = ?").doesNotContain("bound-hash").endsWith("FOR UPDATE");
    }
    @Test void idAndCreatorLockQueriesAreBoundFixedLedgerAndDeterministic() {
        store.lockById(51); store.lockCreatedBy(7);
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),any(RowMapper.class),eq(51L));
        assertThat(normalize(sql.getValue())).contains("ledger_id = 1 AND id = ?").endsWith("FOR UPDATE");
        verify(jdbc).query(sql.capture(),any(RowMapper.class),eq(7L));
        assertThat(normalize(sql.getValue())).contains("created_by = ?", "status = 'ACTIVE'", "ORDER BY id").endsWith("FOR UPDATE");
    }
    @Test void consumeBindsAllThreeStateFieldsAndChecksUnexpiredActiveRow() {
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertThat(store.use(51,8,now)).isEqualTo(1);
        var sql=ArgumentCaptor.forClass(String.class); var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(),args.capture());
        assertThat(normalize(sql.getValue())).contains("SET status = 'USED', used_by = ?, used_at = ?", "ledger_id = 1 AND id = ?", "status = 'ACTIVE' AND expires_at > ?");
        assertThat(args.getValue()).containsExactly(8L,Timestamp.from(now),51L,Timestamp.from(now));
    }
    @Test void revokeIsConditionalWithoutOverwritingConsumption() {
        store.revoke(51,now);
        var sql=ArgumentCaptor.forClass(String.class); var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(),args.capture());
        assertThat(normalize(sql.getValue())).contains("SET status = 'REVOKED'", "status = 'ACTIVE' AND expires_at > ?").doesNotContain("used_by =", "used_at =");
        assertThat(args.getValue()).containsExactly(51L,Timestamp.from(now));
    }
    @Test void countAndItemsShareBoundEffectiveStatePolicyAndNoSensitiveProjection() {
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(3L);
        assertThat(store.count("EXPIRED",now)).isEqualTo(3);
        store.list("EXPIRED",now,20,40);
        var countSql=ArgumentCaptor.forClass(String.class); var countArgs=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(countSql.capture(),eq(Long.class),countArgs.capture());
        var listSql=ArgumentCaptor.forClass(String.class); var listArgs=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(listSql.capture(),any(RowMapper.class),listArgs.capture());
        for(String value:List.of(countSql.getValue(),listSql.getValue())) {
            assertThat(normalize(value)).contains("WHEN i.status <> 'ACTIVE' THEN i.status WHEN i.expires_at <= ? THEN 'EXPIRED'",
                    "u.status = 'ACTIVE' AND cm.status = 'ACTIVE'", "cm.role IN ('OWNER','ADMIN') AND l.status = 'ACTIVE'",
                    "i.ledger_id = 1", "WHERE effective_status = ?").doesNotContain("token_hash", "FOR UPDATE");
        }
        assertThat(normalize(listSql.getValue())).endsWith("ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        assertThat(countArgs.getValue()).containsExactly(Timestamp.from(now),"EXPIRED");
        assertThat(listArgs.getValue()).containsExactly(Timestamp.from(now),"EXPIRED",20,40);
        verifyNoMoreInteractions(jdbc);
    }
    @Test void nullableListUsageFieldsMapAsNullAndNoFilterOmitsOnlyStatusParameter() throws Exception {
        var row=mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(51L); when(row.getLong("created_by")).thenReturn(7L);
        when(row.getString("created_by_name")).thenReturn("Current name");
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(now.minusSeconds(60)));
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(now)); when(row.getString("effective_status")).thenReturn("EXPIRED");
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->List.of(((RowMapper<?>)call.getArgument(1)).mapRow(row,0)));
        var result=store.list(null,now,20,0).getFirst();
        assertThat(result.createdByName()).isEqualTo("Current name"); assertThat(result.status()).isEqualTo("EXPIRED");
        assertThat(result.usedBy()).isNull(); assertThat(result.usedAt()).isNull();
        var args=ArgumentCaptor.forClass(Object[].class); verify(jdbc).query(anyString(),any(RowMapper.class),args.capture());
        assertThat(args.getValue()).containsExactly(Timestamp.from(now),20,0);
    }
    static String normalize(String value) { return value.replaceAll("\\s+"," ").trim(); }
}
