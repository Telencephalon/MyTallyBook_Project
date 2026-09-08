package com.mytallybook.accountbook.category.store;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCategoryStoreTests {
    @Test
    void listAndReferenceQueriesStayInLedgerAndIncludeDeletedEntries() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new JdbcCategoryStore(jdbc);
        store.list("EXPENSE", "ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7L))).thenReturn(2L);
        assertThat(store.referenceCount(7)).isEqualTo(2);

        verify(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), eq(7L));
        assertThat(normalize(sql.getValue()))
                .contains("ledger_id = 1", "category_id = ?")
                .doesNotContain("deleted_at");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
