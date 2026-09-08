package com.mytallybook.accountbook.statistics;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import com.mytallybook.accountbook.statistics.store.StatisticsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(StatisticsHttpTests.Fixtures.class)
class StatisticsHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthStore auth;
    @MockitoBean MemberStore members;
    @MockitoBean StatisticsStore store;

    @BeforeEach
    void setup() {
        var config = new AuthStore.AppConfigState(true, 10, 1);
        var ledger = new MemberStore.LedgerState(1, 1, 10, "ACTIVE", 1);
        var rows = List.of(new MemberStore.MemberState(11, 1, "ACTIVE", MemberRole.OWNER,
                "ACTIVE", "昵称", null, Instant.EPOCH));
        when(auth.readAppConfig()).thenReturn(config);
        when(members.readLedger()).thenReturn(Optional.of(ledger));
        when(members.readMembers()).thenReturn(rows);
        when(store.summary(any())).thenReturn(new StatisticsStore.SummaryRow(new BigDecimal("100.30"), new BigDecimal("30.05"), 3));
        when(store.daily(any())).thenReturn(List.of(new StatisticsStore.DailyRow(LocalDate.of(2024, 9, 1), new BigDecimal("100.30"), new BigDecimal("30.05"), 3)));
        when(store.categories(any(), any())).thenReturn(List.of(new StatisticsStore.RankingRow(7, "餐饮", new BigDecimal("30.05"), 1)));
        when(store.accounts(any())).thenReturn(List.of(new StatisticsStore.AccountRow(8, "现金", new BigDecimal("100.30"), new BigDecimal("30.05"), 3)));
        when(store.members(any(), any())).thenReturn(List.of(new StatisticsStore.RankingRow(1, "历史成员", new BigDecimal("30.05"), 1)));
    }

    @Test
    void fiveExactGetPathsReturnTypedMoneyStrings() throws Exception {
        mvc.perform(get("/api/v1/statistics/monthly-summary?month=2024-09").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.income").value("100.30"))
                .andExpect(jsonPath("$.data.net").value("70.25")).andExpect(jsonPath("$.data.entryCount").value(3));
        mvc.perform(get("/api/v1/statistics/daily-trend?month=2024-09").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].date").value("2024-09-01"))
                .andExpect(jsonPath("$.data.items[0].income").value("100.30"));
        mvc.perform(get("/api/v1/statistics/categories?month=2024-09&entryType=EXPENSE").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value("30.05"))
                .andExpect(jsonPath("$.data.items[0].percentage").value("100.00"));
        mvc.perform(get("/api/v1/statistics/accounts?month=2024-09").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].net").value("70.25"));
        mvc.perform(get("/api/v1/statistics/members?month=2024-09&entryType=EXPENSE").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].name").value("历史成员"));
    }

    @Test
    void anonymousAndUnsafeQueryInputsAreRejectedBeforeAggregation() throws Exception {
        mvc.perform(get("/api/v1/statistics/monthly-summary?month=2024-09")).andExpect(status().isUnauthorized());
        clearInvocations(store);
        mvc.perform(get("/api/v1/statistics/monthly-summary?month=2024-9").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/statistics/categories?entryType=expense").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary
        SessionTokenVerifier verifier() {
            return token -> "owner".equals(token)
                    ? Optional.of(new CurrentUser(1, 1, 11, MemberRole.OWNER)) : Optional.empty();
        }

        @Bean
        org.springframework.transaction.PlatformTransactionManager transactions() {
            return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() { return new Object(); }
                protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
                protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            };
        }
    }
}
