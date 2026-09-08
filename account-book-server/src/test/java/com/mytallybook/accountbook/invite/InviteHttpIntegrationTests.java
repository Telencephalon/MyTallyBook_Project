package com.mytallybook.accountbook.invite;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import java.util.List;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.member.store.MemberStore;
import com.mytallybook.accountbook.invite.store.InviteStore;
import com.mytallybook.accountbook.audit.AuditLogService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(InviteHttpIntegrationTests.Fixtures.class)
class InviteHttpIntegrationTests {
    @Autowired MockMvc mvc;
    @MockitoBean AuthStore auth;
    @MockitoBean MemberStore members;
    @MockitoBean InviteStore invites;
    @MockitoBean AuditLogService audit;
    @MockitoBean com.mytallybook.accountbook.auth.wechat.WechatSessionClient wechat;
    @BeforeEach void setup() {
        var config=new AuthStore.AppConfigState(true,10,1);
        var ledger=new MemberStore.LedgerState(1,1,10,"ACTIVE",1);
        var rows=List.of(new MemberStore.MemberState(11,1,"ACTIVE",MemberRole.OWNER,"ACTIVE","Owner",null,Instant.EPOCH),
                new MemberStore.MemberState(12,2,"ACTIVE",MemberRole.MEMBER,"ACTIVE","Member",null,Instant.EPOCH));
        when(auth.lockAppConfig()).thenReturn(config);
        when(auth.readAppConfig()).thenReturn(config);
        when(members.lockLedger()).thenReturn(Optional.of(ledger));
        when(members.readLedger()).thenReturn(Optional.of(ledger));
        when(members.lockMembers()).thenReturn(rows);
        when(members.readMembers()).thenReturn(rows);
        when(invites.insert(anyString(),eq(1L),any(),any())).thenReturn(51L);
        when(invites.insert(anyString(),eq(3L),any(),any())).thenReturn(52L);
    }
    @Test void ownerCanCreateWithSafeEnvelope() throws Exception {
        mvc.perform(post("/api/v1/invites").header("Authorization", "Bearer owner-fixture")
                .header("X-Request-Id", "invite-http-test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInHours\":24}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.requestId").value("invite-http-test"))
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist());
    }
    @Test void memberCannotCreate() throws Exception {
        mvc.perform(post("/api/v1/invites").header("Authorization", "Bearer member-fixture")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @Test void fractionalHoursAreNotSilentlyCoerced() throws Exception {
        mvc.perform(post("/api/v1/invites").header("Authorization", "Bearer owner-fixture")
                .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInHours\":1.5}"))
                .andExpect(status().isBadRequest());
    }
    @Test void nullBlankStringAndOutOfRangeHoursAreRejected() throws Exception {
        for(String value:List.of("null","\"\"","\"24\"","0","169","-1","2147483648","true","[]","{}")) {
            mvc.perform(post("/api/v1/invites").header("Authorization","Bearer owner-fixture")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"expiresInHours\":"+value+"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(invites,audit);
    }
    @Test void privilegedUnknownFieldsCannotInfluenceStoredCreatorOrRole() throws Exception {
        mvc.perform(post("/api/v1/invites").header("Authorization","Bearer owner-fixture")
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\",\"ledgerId\":99,\"createdBy\":99}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(51));
        verify(invites).insert(anyString(),eq(1L),any(),any());
        verify(members,never()).insertMember(anyLong(),any());
    }
    @Test void listingReturnsOnlySafeContractFieldsAndNullUsageFields() throws Exception {
        when(invites.count(eq("EXPIRED"),any())).thenReturn(1L);
        when(invites.list(eq("EXPIRED"),any(),eq(20),eq(0))).thenReturn(List.of(
                new InviteView(51,1,"Owner",Instant.EPOCH,Instant.EPOCH,"EXPIRED",null,null)));
        mvc.perform(get("/api/v1/invites?status=EXPIRED").header("Authorization","Bearer owner-fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.items[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.items[0].token").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].usedBy").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].usedAt").value(org.hamcrest.Matchers.nullValue()));
        verifyNoInteractions(audit);
    }
    @Test void badPagingFiltersAndUnsafeIdsAreRejected() throws Exception {
        for(String query:List.of("page=0","page=1.5","page=2147483648","pageSize=0","pageSize=51",
                "page=2147483647&pageSize=50","status=active","status=","status=ACTIVE%27")) {
            mvc.perform(get("/api/v1/invites?"+query).header("Authorization","Bearer owner-fixture"))
                    .andExpect(status().isBadRequest());
        }
        for(String id:List.of("0","-1","1.5","9007199254740992","9223372036854775808")) {
            mvc.perform(delete("/api/v1/invites/"+id).header("Authorization","Bearer owner-fixture"))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test void memberCannotListOrRevokeAndAnonymousCannotManage() throws Exception {
        mvc.perform(get("/api/v1/invites").header("Authorization","Bearer member-fixture")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/invites/51").header("Authorization","Bearer member-fixture")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/invites")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/invites").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/invites/51")).andExpect(status().isUnauthorized());
        verifyNoInteractions(invites,audit);
    }
    @Test void activeAdminCanCreateListAndRevoke() throws Exception {
        var rows=new java.util.ArrayList<>(members.lockMembers());
        rows.add(new MemberStore.MemberState(13,3,"ACTIVE",MemberRole.ADMIN,"ACTIVE","Admin",null,Instant.EPOCH));
        when(members.lockMembers()).thenReturn(rows); when(members.readMembers()).thenReturn(rows);
        when(invites.lockById(51)).thenReturn(Optional.of(new InviteStore.InviteRow(51,1,1,Instant.EPOCH,Instant.now().plusSeconds(3600),"ACTIVE",null,null)));
        when(invites.revoke(eq(51L),any())).thenReturn(1);
        mvc.perform(post("/api/v1/invites").header("Authorization","Bearer admin-fixture").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/invites").header("Authorization","Bearer admin-fixture")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/invites/51").header("Authorization","Bearer admin-fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REVOKED"));
    }
    @Test void anonymousAcceptanceReturnsExistingAuthenticationEnvelope() throws Exception {
        when(wechat.exchange("fresh-code")).thenReturn(new com.mytallybook.accountbook.auth.wechat.WechatIdentity("new-openid",null));
        when(invites.lockByHash(anyString())).thenReturn(Optional.of(new InviteStore.InviteRow(51,1,1,Instant.EPOCH,Instant.now().plusSeconds(3600),"ACTIVE",null,null)));
        when(auth.insertUser(eq("new-openid"),isNull(),eq("微信用户"),any())).thenReturn(4L);
        when(members.insertMember(eq(4L),any())).thenReturn(14L);
        when(invites.use(eq(51L),eq(4L),any())).thenReturn(1);
        mvc.perform(post("/api/v1/auth/invites/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"fresh-code\",\"inviteToken\":\""+"A".repeat(43)+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.data.token").isString()).andExpect(jsonPath("$.data.expiresAt").isString())
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist()).andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    @Test void malformedAnonymousAcceptanceIs400Not401AndDoesNotCallWechat() throws Exception {
        for(String body:List.of("{}","{\"code\":null,\"inviteToken\":null}","{\"code\":\" \",\"inviteToken\":\"bad\"}","{\"code\":\"code\",\"inviteToken\":\"bad\"}")) {
            mvc.perform(post("/api/v1/auth/invites/accept").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(wechat,invites,audit);
    }
    @TestConfiguration(proxyBeanMethods = false) static class Fixtures {
        @Bean org.springframework.transaction.PlatformTransactionManager invitationTransactions() {
            return com.mytallybook.accountbook.support.TestTransactions.template().getTransactionManager();
        }
        @Bean @Primary SessionTokenVerifier inviteVerifier() {
            return token -> switch (token) {
                case "owner-fixture" -> Optional.of(new CurrentUser(1, 1, 11, MemberRole.OWNER));
                case "member-fixture" -> Optional.of(new CurrentUser(2, 1, 12, MemberRole.MEMBER));
                case "admin-fixture" -> Optional.of(new CurrentUser(3, 1, 13, MemberRole.ADMIN));
                default -> Optional.empty();
            };
        }
    }
}
