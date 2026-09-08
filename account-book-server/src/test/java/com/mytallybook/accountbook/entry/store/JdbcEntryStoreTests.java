package com.mytallybook.accountbook.entry.store;

import com.mytallybook.accountbook.entry.EntryFilters;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcEntryStoreTests {
    @Test
    void listCountAndDetailStayInLedgerUseHistoricalJoinsAndDeterministicOrder() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcEntryStore(jdbc);
        var filters = EntryFilters.parse("2026-09-01", "9999-12-31", "EXPENSE",
                "7", "8", "2", "%_\\", "2", "20");

        store.list(filters);
        store.count(filters);
        store.find(40);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        String list = normalize(sql.getAllValues().get(0));
        String detail = normalize(sql.getAllValues().get(1));
        String count = normalize(sql.getAllValues().get(2));
        assertThat(list).contains("e.ledger_id=1", "e.deleted_at IS NULL", "c.ledger_id=e.ledger_id",
                        "fa.ledger_id=e.ledger_id", "ORDER BY e.entry_date DESC,e.id DESC", "LIMIT ? OFFSET ?")
                .doesNotContain("c.status = 'ACTIVE'", "fa.status = 'ACTIVE'");
        assertThat(detail).contains("e.id=?", "e.ledger_id=1", "e.deleted_at IS NULL");
        assertThat(count).contains("COUNT(*)", "e.note LIKE ? ESCAPE '\\\\'");
        assertThat(list).contains("e.entry_date <=?");
    }

    @Test
    void literalKeywordEscapesPercentUnderscoreAndBackslash() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcEntryStore(jdbc);
        store.count(EntryFilters.parse(null, null, null, null, null, null, "%_\\", null, null));

        var arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(Long.class), arguments.capture());
        assertThat(arguments.getValue()).contains("%\\%\\_\\\\%");
    }

    @Test
    void updateDeleteAndReplayQueriesEnforceLedgerSoftDeleteAndVersion() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcEntryStore(jdbc);
        var now = Instant.parse("2026-09-06T01:00:00Z");

        store.findByClientRequestId("11111111-2222-4333-8444-555555555555");
        store.update(40, "EXPENSE", new BigDecimal("3.40"), 7, 8,
                LocalDate.of(2026, 9, 6), null, 2, now, 3);
        store.softDelete(40, now, 2, 3);

        var querySql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(querySql.capture(), any(RowMapper.class), eq("11111111-2222-4333-8444-555555555555"));
        assertThat(normalize(querySql.getValue())).contains("client_request_id=?").doesNotContain("e.deleted_at IS NULL");
        var updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(updateSql.capture(), any(Object[].class));
        assertThat(normalize(updateSql.getAllValues().get(0)))
                .isEqualTo("UPDATE book_entry SET entry_type=?,amount=?,category_id=?,account_id=?,entry_date=?,note=?,updated_by=?,updated_at=?,version=version+1 WHERE ledger_id=1 AND id=? AND deleted_at IS NULL AND version=?");
        assertThat(normalize(updateSql.getAllValues().get(1)))
                .isEqualTo("UPDATE book_entry SET deleted_at=?,updated_at=?,updated_by=?,version=version+1 WHERE ledger_id=1 AND id=? AND deleted_at IS NULL AND version=?");
    }

    @Test
    void insertAndSelectUseTheV2CreatedByOnlyContract() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcEntryStore(jdbc);
        store.find(40);

        var select = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(select.capture(), any(RowMapper.class), eq(40L));
        assertThat(normalize(select.getValue())).contains("e.created_by").doesNotContain("member_id");
    }

    @Test
    void creatorsUnionActiveMembersWithHistoricalCreatorsWithoutAuthenticationFields() {
        var jdbc = mock(JdbcTemplate.class);
        new JdbcEntryStore(jdbc).creators();

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class));
        assertThat(normalize(sql.getValue()))
                .contains("UNION", "lm.status='ACTIVE'", "u.status='ACTIVE'", "book_entry", "created_by", "display_name", "nickname")
                .doesNotContain("openid", "unionid", "auth_session");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\s*=\\s*", "=").replaceAll("\\s*,\\s*", ",").trim();
    }
}
