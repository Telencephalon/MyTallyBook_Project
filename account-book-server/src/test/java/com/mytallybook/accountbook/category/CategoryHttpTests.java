package com.mytallybook.accountbook.category;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.category.store.CategoryStore;
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
@Import(CategoryHttpTests.Fixtures.class)
class CategoryHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthStore auth;
    @MockitoBean MemberStore members;
    @MockitoBean CategoryStore store;
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
    void anonymousIsRejectedAndEveryRoleReadsFilters() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
        when(store.list("EXPENSE", "DISABLED")).thenReturn(List.of(new CategoryStore.CategoryRow(
                7, "EXPENSE", "餐饮", null, "#112233", 2, false, "DISABLED")));
        for (String role : List.of("owner", "admin", "member")) {
            mvc.perform(get("/api/v1/categories?entryType=EXPENSE&status=DISABLED")
                            .header("Authorization", "Bearer " + role))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].entryType").value("EXPENSE"))
                    .andExpect(jsonPath("$.data.items[0].systemDefault").value(false));
        }
        mvc.perform(get("/api/v1/categories?entryType=bad").header("Authorization", "Bearer owner"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void immutableUnknownAndWrongShapesAreRejectedBeforeMutation() throws Exception {
        for (String body : List.of(
                "{\"entryType\":\"EXPENSE\",\"name\":\"餐饮\",\"ledgerId\":2}",
                "{\"entryType\":\"EXPENSE\",\"name\":7}",
                "{\"name\":\"餐饮\",\"entryType\":\"expense\"}")) {
            mvc.perform(post("/api/v1/categories").header("Authorization", "Bearer owner")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(store, never()).insert(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void memberWriteDeniedAndPutPatchShareEditableContract() throws Exception {
        mvc.perform(post("/api/v1/categories").header("Authorization", "Bearer member")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"entryType\":\"EXPENSE\",\"name\":\"餐饮\"}"))
                .andExpect(status().isForbidden());
        when(store.find(7)).thenReturn(Optional.of(new CategoryStore.CategoryRow(
                7, "EXPENSE", "新名", null, null, 0, false, "ACTIVE")));
        when(store.update(eq(7L), any(), any(), any(), anyInt(), any())).thenReturn(1);
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH)) {
            mvc.perform(request(method, "/api/v1/categories/7").header("Authorization", "Bearer admin")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"新名\",\"icon\":null,\"color\":null,\"sortNo\":0,\"status\":\"ACTIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entryType").value("EXPENSE"));
        }
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
