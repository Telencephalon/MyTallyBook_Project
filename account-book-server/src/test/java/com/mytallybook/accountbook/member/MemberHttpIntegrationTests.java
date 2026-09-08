package com.mytallybook.accountbook.member;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.invite.store.InviteStore;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MemberHttpIntegrationTests.Fixtures.class)
class MemberHttpIntegrationTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthStore auth;
    @MockitoBean MemberStore members;
    @MockitoBean InviteStore invites;
    @MockitoBean AuditLogService audit;

    @BeforeEach void setup() {
        var config = new AuthStore.AppConfigState(true, 8, 1);
        var ledger = new MemberStore.LedgerState(1, 1, 9, "ACTIVE", 1);
        var rows = List.of(row(13, 3, MemberRole.ADMIN), row(12, 2, MemberRole.MEMBER), row(11, 1, MemberRole.OWNER));
        when(auth.lockAppConfig()).thenReturn(config);
        when(auth.readAppConfig()).thenReturn(config);
        when(members.lockLedger()).thenReturn(Optional.of(ledger));
        when(members.readLedger()).thenReturn(Optional.of(ledger));
        when(members.lockMembers()).thenReturn(rows);
        when(members.readMembers()).thenReturn(rows);
    }

    static MemberStore.MemberState row(long id, long userId, MemberRole role) {
        return new MemberStore.MemberState(id, userId, "ACTIVE", role, "ACTIVE", "Name" + userId, null, Instant.EPOCH);
    }

    @Test void everyActiveRoleCanReadLiteralSafeMemberContract() throws Exception {
        for (String role : List.of("owner", "admin", "member")) {
            mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + role)
                    .header("X-Request-Id", "members-list"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                    .andExpect(jsonPath("$.requestId").value("members-list"))
                    .andExpect(jsonPath("$.data.activeCount").value(3))
                    .andExpect(jsonPath("$.data.maxMembers").value(8))
                    .andExpect(jsonPath("$.data.ownerUserId").value(1))
                    .andExpect(jsonPath("$.data.items[0].memberId").value(11))
                    .andExpect(jsonPath("$.data.items[0].userId").value(1))
                    .andExpect(jsonPath("$.data.items[0].nickname").value("Name1"))
                    .andExpect(jsonPath("$.data.items[0].displayName").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.items[0].role").value("OWNER"))
                    .andExpect(jsonPath("$.data.items[0].joinedAt").value("1970-01-01T00:00:00Z"))
                    .andExpect(jsonPath("$.data.items[0].openid").doesNotExist())
                    .andExpect(jsonPath("$.data.items[0].tokenHash").doesNotExist());
        }
    }

    @Test void ownerPutAndPatchRoleNoOpUseSameContract() throws Exception {
        for (var method : List.of(org.springframework.http.HttpMethod.PUT, org.springframework.http.HttpMethod.PATCH)) {
            mvc.perform(request(method, "/api/v1/members/12/role").header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberId").value(12))
                    .andExpect(jsonPath("$.data.role").value("MEMBER"));
        }
        verifyNoInteractions(audit, invites);
    }

    @Test void adminAndMemberCannotChangeRoles() throws Exception {
        for (String role : List.of("admin", "member")) {
            mvc.perform(put("/api/v1/members/12/role").header("Authorization", "Bearer " + role)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @Test void staleOwnerCannotTransferAfterStoredDemotion() throws Exception {
        when(members.lockLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1, 3, 9, "ACTIVE", 2)));
        when(members.lockMembers()).thenReturn(List.of(row(11, 1, MemberRole.MEMBER), row(12, 2, MemberRole.MEMBER), row(13, 3, MemberRole.OWNER)));
        mvc.perform(post("/api/v1/members/12/transfer-ownership").header("Authorization", "Bearer owner"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(invites, audit);
    }

    @Test void privilegedUnknownFieldsCannotChangeIdentityStatusOrAlias() throws Exception {
        when(members.changeRole(12, MemberRole.MEMBER, MemberRole.ADMIN)).thenReturn(1);
        mvc.perform(put("/api/v1/members/12/role").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"memberId\":13,\"userId\":99,\"ledgerId\":99,\"status\":\"REMOVED\",\"displayName\":\"overwrite\",\"ownerUserId\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberId").value(12))
                .andExpect(jsonPath("$.data.userId").value(2)).andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.displayName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(6)));
        verify(members).changeRole(12, MemberRole.MEMBER, MemberRole.ADMIN);
        verify(members, never()).remove(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test void invalidRoleShapesIncludingEnumOrdinalsFailBeforeMutation() throws Exception {
        for (String value : List.of("null", "\"OWNER\"", "\"member\"", "\" ADMIN \"", "0", "1", "1.2", "true", "[]", "{}")) {
            mvc.perform(put("/api/v1/members/12/role").header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":" + value + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(put("/api/v1/members/12/role").header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(audit, invites);
    }

    @Test void nonpositiveOverflowAndUnsafePathIdsFailSafely() throws Exception {
        for (String id : List.of("0", "-1", "9007199254740992", "9223372036854775808", "not-a-number")) {
            mvc.perform(put("/api/v1/members/" + id + "/role").header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete("/api/v1/members/" + id).header("Authorization", "Bearer owner"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/v1/members/" + id + "/transfer-ownership").header("Authorization", "Bearer owner"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(audit, invites);
    }

    @Test void anonymousMemberRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/members")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/members/12")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/members/12/transfer-ownership")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/members/12/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void removalDistinguishesOwnerConflictAndForbiddenAdminTargeting() throws Exception {
        mvc.perform(delete("/api/v1/members/11").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_TRANSFER_REQUIRED"));
        mvc.perform(delete("/api/v1/members/13").header("Authorization", "Bearer owner"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_DEMOTION_REQUIRED"));
        mvc.perform(delete("/api/v1/members/11").header("Authorization", "Bearer admin"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(delete("/api/v1/members/13").header("Authorization", "Bearer member"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(audit, invites);
    }

    @Test void successfulRemovalAndSelfLeaveReturnDistinctStatusesAndRevokeSessions() throws Exception {
        when(members.remove(org.mockito.ArgumentMatchers.eq(12L), org.mockito.ArgumentMatchers.eq(MemberRole.MEMBER),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        mvc.perform(delete("/api/v1/members/12").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberId").value(12))
                .andExpect(jsonPath("$.data.status").value("REMOVED"));
        mvc.perform(delete("/api/v1/members/12").header("Authorization", "Bearer member"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("LEFT"));
        verify(auth, times(2)).revokeAllSessions(org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any());
        verify(auth, never()).revokeSession(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test void successfulTransferReturnsOnlyNewAndPreviousOwnerIds() throws Exception {
        when(members.changeRole(11, MemberRole.OWNER, MemberRole.MEMBER)).thenReturn(1);
        when(members.changeRole(12, MemberRole.MEMBER, MemberRole.OWNER)).thenReturn(1);
        when(members.transferOwner(1, 2, 1)).thenReturn(1);
        var first = List.of(row(11, 1, MemberRole.OWNER), row(12, 2, MemberRole.MEMBER), row(13, 3, MemberRole.ADMIN));
        var after = List.of(row(11, 1, MemberRole.MEMBER), row(12, 2, MemberRole.OWNER), row(13, 3, MemberRole.ADMIN));
        when(members.lockMembers()).thenReturn(first, after);
        when(members.lockLedger()).thenReturn(Optional.of(new MemberStore.LedgerState(1, 1, 9, "ACTIVE", 1)),
                Optional.of(new MemberStore.LedgerState(1, 2, 9, "ACTIVE", 2)));
        mvc.perform(post("/api/v1/members/12/transfer-ownership").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ownerUserId").value(2))
                .andExpect(jsonPath("$.data.previousOwnerUserId").value(1))
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.aMapWithSize(2)));
    }

    @TestConfiguration(proxyBeanMethods = false) static class Fixtures {
        @Bean org.springframework.transaction.PlatformTransactionManager memberTransactions() {
            // Resource-less boundary fixture that recognizes an existing transaction for MANDATORY.
            // This validates proxy participation, not physical database atomicity or locks.
            return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected boolean isExistingTransaction(Object transaction) {
                    return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
                }
                @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}
                @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
                @Override protected void doSetRollbackOnly(org.springframework.transaction.support.DefaultTransactionStatus status) {}
            };
        }
        @Bean @Primary SessionTokenVerifier memberVerifier() {
            return token -> switch (token) {
                case "owner" -> Optional.of(new CurrentUser(1, 1, 11, MemberRole.OWNER));
                case "member" -> Optional.of(new CurrentUser(2, 1, 12, MemberRole.MEMBER));
                case "admin" -> Optional.of(new CurrentUser(3, 1, 13, MemberRole.ADMIN));
                default -> Optional.empty();
            };
        }
    }
}
