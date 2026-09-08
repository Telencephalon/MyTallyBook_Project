package com.mytallybook.accountbook.account;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.account.store.AccountStore;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
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
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AccountHttpTests.Fixtures.class)
class AccountHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthStore auth;
    @MockitoBean MemberStore members;
    @MockitoBean AccountStore store;
    @MockitoBean AuditLogService audit;

    @BeforeEach
    void setup() {
        var config = new AuthStore.AppConfigState(true, 10, 1);
        var ledger = new MemberStore.LedgerState(1, 1, 10, "ACTIVE", 1);
        var rows = List.of(row(11, 1, MemberRole.OWNER), row(12, 2, MemberRole.MEMBER),
                row(13, 3, MemberRole.ADMIN));
        when(auth.readAppConfig()).thenReturn(config);
        when(auth.lockAppConfig()).thenReturn(config);
        when(members.readLedger()).thenReturn(Optional.of(ledger));
        when(members.lockLedger()).thenReturn(Optional.of(ledger));
        when(members.readMembers()).thenReturn(rows);
        when(members.lockMembers()).thenReturn(rows);
    }

    @Test
    void exactMoneySerializesAsStringsAndFiltersValidate() throws Exception {
        when(store.list("ACTIVE")).thenReturn(List.of(new AccountStore.AccountRow(
                7, "现金", "CASH", new BigDecimal("-1.20"), new BigDecimal("0.30"), 0, "ACTIVE", 2)));
        mvc.perform(get("/api/v1/accounts?status=ACTIVE").header("Authorization", "Bearer member"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].initialBalance").value("-1.20"))
                .andExpect(jsonPath("$.data.items[0].currentBalance").value("0.30"));
        mvc.perform(get("/api/v1/accounts?status=BAD").header("Authorization", "Bearer member"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void numericMoneyAndImmutableFieldsAreRejected() throws Exception {
        for (String body : List.of(
                "{\"name\":\"现金\",\"accountType\":\"CASH\",\"initialBalance\":1.2}",
                "{\"name\":\"现金\",\"accountType\":\"CASH\",\"initialBalance\":\"0\",\"currentBalance\":\"1\"}")) {
            mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer owner")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(store, never()).insert(any(), any(), any(), anyInt(), any());
    }

    @Test
    void updateRequiresVersionAndDeleteRequiresQueryVersion() throws Exception {
        when(store.find(7)).thenReturn(Optional.of(new AccountStore.AccountRow(
                7, "现金", "CASH", BigDecimal.ZERO, BigDecimal.ZERO, 0, "ACTIVE", 2)));
        mvc.perform(put("/api/v1/accounts/7").header("Authorization", "Bearer owner")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"现金\",\"sortNo\":0,\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/accounts/7").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/accounts/7").header("Authorization", "Bearer owner")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"现金\",\"sortNo\":0,\"status\":\"ACTIVE\",\"version\":2,\"accountType\":\"BANK\"}"))
                .andExpect(status().isBadRequest());
        verify(store, never()).update(anyLong(), any(), anyInt(), any(), anyLong());
    }

    private static MemberStore.MemberState row(long memberId, long userId, MemberRole role) {
        return new MemberStore.MemberState(memberId, userId, "ACTIVE", role,
                "ACTIVE", "n", null, Instant.EPOCH);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary
        SessionTokenVerifier verifier() {
            return token -> switch (token) {
                case "owner" -> Optional.of(new CurrentUser(1, 1, 11, MemberRole.OWNER));
                case "admin" -> Optional.of(new CurrentUser(3, 1, 13, MemberRole.ADMIN));
                case "member" -> Optional.of(new CurrentUser(2, 1, 12, MemberRole.MEMBER));
                default -> Optional.empty();
            };
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
