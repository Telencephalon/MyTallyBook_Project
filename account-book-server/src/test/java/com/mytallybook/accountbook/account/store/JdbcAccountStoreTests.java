package com.mytallybook.accountbook.account.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcAccountStoreTests {
    @Test
    void personalBalanceBindsCreatorInJoinAndOmitsSharedOpeningMoney() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcAccountStore(jdbc);
        store.list("ACTIVE", 2L);
        store.list(null, 2L);
        store.find(7L, 2L);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(2L), eq("ACTIVE"));
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(2L));
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(2L), eq(7L));
        for (String value : sql.getAllValues()) {
            assertThat(normalize(value))
                    .contains("0 AS initial_balance")
                    .contains("COALESCE(SUM(")
                    .doesNotContain("fa.initial_balance +")
                    .contains("AND e.deleted_at IS NULL AND e.created_by = ? WHERE fa.ledger_id = 1");
        }
    }
    @Test
    void balanceAndReferenceSqlUseFixedLedgerWithCorrectSoftDeleteRules() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcAccountStore(jdbc);
        store.list("ACTIVE");
        store.find(7);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(1L);
        store.referenceCount(7);

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq("ACTIVE"));
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(7L));
        String select = "SELECT fa.id, fa.name, fa.account_type, fa.initial_balance, fa.sort_no, fa.status, fa.version, "
                + "fa.initial_balance + COALESCE(SUM( CASE WHEN e.entry_type = 'INCOME' THEN e.amount ELSE -e.amount END ), 0) AS current_balance "
                + "FROM fund_account fa LEFT JOIN book_entry e ON e.account_id = fa.id AND e.ledger_id = fa.ledger_id "
                + "AND e.deleted_at IS NULL WHERE fa.ledger_id = 1";
        String group = "GROUP BY fa.id, fa.name, fa.account_type, fa.initial_balance, fa.sort_no, fa.status, fa.version";
        assertThat(normalize(sql.getAllValues().get(0)))
                .isEqualTo(select + " AND fa.status = ? " + group + " ORDER BY fa.sort_no, fa.id");
        assertThat(normalize(sql.getAllValues().get(1)))
                .isEqualTo(select + " AND fa.id = ? " + group);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), eq(7L));
        assertThat(sql.getValue()).doesNotContain("deleted_at");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
