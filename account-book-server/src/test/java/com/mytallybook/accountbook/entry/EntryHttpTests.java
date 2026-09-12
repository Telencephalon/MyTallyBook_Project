package com.mytallybook.accountbook.entry;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.entry.store.EntryStore;
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
import org.springframework.http.HttpMethod;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EntryHttpTests.Fixtures.class)
class EntryHttpTests {
    private static final String UUID = "11111111-2222-4333-8444-555555555555";
    @Autowired MockMvc mvc;
    @MockitoBean AuthStore auth;
    @MockitoBean MemberStore members;
    @MockitoBean EntryStore store;
    @MockitoBean AuditLogService audit;

    @BeforeEach
    void setup() {
        var config = new AuthStore.AppConfigState(true, 10, 1);
        var ledger = new MemberStore.LedgerState(1, 1, 10, "ACTIVE", 1);
        var rows = List.of(row(11, 1, MemberRole.OWNER), row(12, 2, MemberRole.MEMBER), row(13, 3, MemberRole.ADMIN));
        when(auth.readAppConfig()).thenReturn(config);
        when(auth.lockAppConfig()).thenReturn(config);
        when(members.readLedger()).thenReturn(Optional.of(ledger));
        when(members.lockLedger()).thenReturn(Optional.of(ledger));
        when(members.readMembers()).thenReturn(rows);
        when(members.lockMembers()).thenReturn(rows);
    }

    @Test
    void allReadAndWritePathsUseTypedContracts() throws Exception {
        when(store.list(any())).thenReturn(List.of(entry()));
        when(store.count(any())).thenReturn(1L);
        when(store.find(40)).thenReturn(Optional.of(entry()));
        when(store.creators()).thenReturn(List.of(new EntryStore.CreatorRow(2, "家庭成员")));
        when(store.findByClientRequestId(UUID)).thenReturn(Optional.empty());
        when(store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "餐饮", "ACTIVE")));
        when(store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "现金", "ACTIVE")));
        when(store.insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any())).thenReturn(40L);
        when(store.update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), eq(0L), any())).thenReturn(1);
        when(store.softDelete(eq(40L), any(), anyLong(), eq(0L))).thenReturn(1);

        mvc.perform(get("/api/v1/entries?entryType=EXPENSE&page=1&pageSize=20").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].amount").value("3.40"))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/v1/entries/40").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.personName").value("张三"));
        mvc.perform(get("/api/v1/entries/creators").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].displayName").value("家庭成员"));
        mvc.perform(post("/api/v1/entries").header("Authorization", "Bearer member")
                        .contentType("application/json").content(createBody().replace("}", ",\"personName\":\"张三\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.clientRequestId").value(UUID));
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH)) {
            mvc.perform(request(method, "/api/v1/entries/40").header("Authorization", "Bearer member")
                            .contentType("application/json").content(updateBody().replace("}", ",\"personName\":\"李四\"}")))
                    .andExpect(status().isOk());
        }
        mvc.perform(delete("/api/v1/entries/40?version=0").header("Authorization", "Bearer member"))
                .andExpect(status().isOk());
    }

    @Test
    void legacyUpdatePreservesPersonNameAndExplicitNullClearsIt() throws Exception {
        when(store.find(40)).thenReturn(Optional.of(entry()));
        when(store.findCategory(7)).thenReturn(Optional.of(new EntryStore.CategoryReference(7, "EXPENSE", "人情", "ACTIVE")));
        when(store.findAccount(8)).thenReturn(Optional.of(new EntryStore.AccountReference(8, "微信", "ACTIVE")));
        when(store.update(anyLong(), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), anyLong(), any())).thenReturn(1);

        mvc.perform(put("/api/v1/entries/40").header("Authorization", "Bearer member")
                .contentType("application/json").content(updateBody())).andExpect(status().isOk());
        verify(store).update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), eq(2L), any(), eq(0L), eq("张三"));

        mvc.perform(patch("/api/v1/entries/40").header("Authorization", "Bearer member")
                .contentType("application/json").content(updateBody().replace("}", ",\"personName\":null}")))
                .andExpect(status().isOk());
        verify(store).update(eq(40L), any(), any(), anyLong(), anyLong(), any(), any(), eq(2L), any(), eq(0L), isNull());
    }

    @Test
    void unknownForgeryAndWrongJsonShapesAreRejectedBeforeMutation() throws Exception {
        for (String body : List.of(
                createBody().replace("}", ",\"personName\":123}"),
                createBody().replace("}", ",\"personName\":{}}"),
                createBody().replace("}", ",\"personName\":\"" + "名".repeat(65) + "\"}"),
                createBody().replace("}", ",\"createdBy\":1}"),
                createBody().replace("}", ",\"ledgerId\":1}"),
                createBody().replace("\"3.40\"", "3.40"),
                updateBody().replace("}", ",\"clientRequestId\":\"" + UUID + "\"}"))) {
            mvc.perform(post("/api/v1/entries").header("Authorization", "Bearer owner")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/entries/40").header("Authorization", "Bearer owner")
                        .contentType("application/json")
                        .content(updateBody().replace("}", ",\"clientRequestId\":\"" + UUID + "\"}")))
                .andExpect(status().isBadRequest());
        verify(store, never()).insert(any(), any(), anyLong(), anyLong(), any(), any(), any(), anyLong(), any());
        verify(store, never()).update(anyLong(), any(), any(), anyLong(), anyLong(), any(), any(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void invalidRangesOverflowIdsAndMissingDeleteVersionAreRejected() throws Exception {
        for (String path : List.of(
                "/api/v1/entries?dateFrom=2026-09-07&dateTo=2026-09-06",
                "/api/v1/entries?page=2147483648",
                "/api/v1/entries?pageSize=51",
                "/api/v1/entries?categoryId=9007199254740992")) {
            mvc.perform(get(path).header("Authorization", "Bearer member")).andExpect(status().isBadRequest());
        }
        mvc.perform(delete("/api/v1/entries/40").header("Authorization", "Bearer member"))
                .andExpect(status().isBadRequest());
    }

    private static String createBody() {
        return "{\"entryType\":\"EXPENSE\",\"amount\":\"3.40\",\"categoryId\":7,\"accountId\":8,"
                + "\"entryDate\":\"2026-09-06\",\"note\":\"晚餐\",\"clientRequestId\":\"" + UUID + "\"}";
    }

    private static String updateBody() {
        return "{\"entryType\":\"EXPENSE\",\"amount\":\"3.40\",\"categoryId\":7,\"accountId\":8,"
                + "\"entryDate\":\"2026-09-06\",\"note\":\"晚餐\",\"version\":0}";
    }

    private static EntryStore.EntryRow entry() {
        return new EntryStore.EntryRow(40, "EXPENSE", new BigDecimal("3.40"), 7, "餐饮", "ACTIVE", 8, "现金", "ACTIVE",
                LocalDate.of(2026, 9, 6), "晚餐", 2, "家庭成员", Instant.parse("2026-09-06T01:00:00Z"),
                Instant.parse("2026-09-06T01:00:00Z"), null, UUID, 0, "张三");
    }

    private static MemberStore.MemberState row(long memberId, long userId, MemberRole role) {
        return new MemberStore.MemberState(memberId, userId, "ACTIVE", role, "ACTIVE", "n", null, Instant.EPOCH);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary SessionTokenVerifier verifier() {
            return token -> switch (token) {
                case "owner" -> Optional.of(new CurrentUser(1, 1, 11, MemberRole.OWNER));
                case "admin" -> Optional.of(new CurrentUser(3, 1, 13, MemberRole.ADMIN));
                case "member" -> Optional.of(new CurrentUser(2, 1, 12, MemberRole.MEMBER));
                default -> Optional.empty();
            };
        }

        @Bean org.springframework.transaction.PlatformTransactionManager transactions() {
            return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() { return new Object(); }
                protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
                protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            };
        }
    }
}
