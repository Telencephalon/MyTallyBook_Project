package com.mytallybook.accountbook.auth;

import com.mytallybook.accountbook.AccountBookServerApplication;
import com.mytallybook.accountbook.audit.AuditLogService;
import com.mytallybook.accountbook.auth.service.AuthResult;
import com.mytallybook.accountbook.auth.service.AuthService;
import com.mytallybook.accountbook.auth.service.AuthState;
import com.mytallybook.accountbook.auth.service.BootstrapService;
import com.mytallybook.accountbook.auth.session.IssuedSessionToken;
import com.mytallybook.accountbook.auth.store.AuthStore;
import com.mytallybook.accountbook.common.error.BusinessException;
import com.mytallybook.accountbook.common.error.ErrorCode;
import com.mytallybook.accountbook.security.CurrentUser;
import com.mytallybook.accountbook.security.MemberRole;
import com.mytallybook.accountbook.security.SessionTokenVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AccountBookServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthHttpIntegrationTests.SecurityTestConfiguration.class)
class AuthHttpIntegrationTests {

    private static final String VALID_TOKEN = "valid-owner-token";
    private static final CurrentUser CURRENT_USER =
            new CurrentUser(101L, 1L, 1001L, MemberRole.OWNER);
    private static final AuthStore.UserProfileView ORIGINAL_PROFILE =
            new AuthStore.UserProfileView(
                    101L,
                    "微信用户",
                    "https://example.invalid/old.png",
                    1L,
                    1001L,
                    MemberRole.OWNER,
                    null
            );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private BootstrapService bootstrapService;

    @MockitoBean
    private AuthStore authStore;

    @MockitoBean
    private AuditLogService auditLogService;

    @BeforeEach
    void currentIdentityFixture() {
        when(authStore.lockLoginMembership(101L)).thenReturn(Optional.of(new AuthStore.LoginMembership(
                101L, "ACTIVE", 1L, 1001L, MemberRole.OWNER, "ACTIVE", "ACTIVE")));
    }

    @Test
    void loginReturnsNeedBootstrapAndInviteRequiredStates() throws Exception {
        when(authService.login(eq("fresh-code"), anyString()))
                .thenReturn(AuthResult.state(AuthState.NEED_BOOTSTRAP));

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fresh-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.state").value("NEED_BOOTSTRAP"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        when(authService.login(eq("unknown-code"), anyString()))
                .thenReturn(AuthResult.state(AuthState.INVITE_REQUIRED));

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"unknown-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("INVITE_REQUIRED"));
    }

    @Test
    void loginAndBootstrapReturnAuthenticatedTokenWithUtcExpiry() throws Exception {
        Instant expiry = Instant.parse("2026-09-30T02:00:00Z");
        AuthResult authenticated = AuthResult.authenticated(
                new IssuedSessionToken("one-time-token", "a".repeat(64), expiry)
        );
        when(authService.login(eq("member-code"), anyString())).thenReturn(authenticated);
        when(bootstrapService.bootstrap(
                eq("owner-code"),
                eq("test-bootstrap-key-123456"),
                anyString()
        )).thenReturn(authenticated);

        mockMvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"member-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.data.token").value("one-time-token"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-09-30T02:00:00Z"));

        mockMvc.perform(post("/api/v1/auth/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "owner-code",
                                  "bootstrapKey": "test-bootstrap-key-123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.data.token").value("one-time-token"));
    }

    @Test
    void bootstrapConflictAndRequestValidationUseStableErrors() throws Exception {
        when(bootstrapService.bootstrap(eq("owner-code"), eq("x".repeat(20)), anyString()))
                .thenThrow(new BusinessException(ErrorCode.ALREADY_INITIALIZED));

        mockMvc.perform(post("/api/v1/auth/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"owner-code","bootstrapKey":"xxxxxxxxxxxxxxxxxxxx"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_INITIALIZED"));

        mockMvc.perform(post("/api/v1/auth/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"bootstrapKey\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.code").exists())
                .andExpect(jsonPath("$.details.bootstrapKey").exists());
    }

    @Test
    void protectedEndpointsRejectAnonymousRequests() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/ledger"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesOnlyTheValidatedBearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("LOGGED_OUT"));

        verify(authService).logout(eq(VALID_TOKEN), eq(CURRENT_USER), anyString());
    }

    @Test
    void currentUserAndLedgerQueriesReturnTheAuthenticatedResources() throws Exception {
        when(authStore.findUserProfile(101L)).thenReturn(Optional.of(ORIGINAL_PROFILE));
        when(authStore.findLedger(1L)).thenReturn(Optional.of(
                new AuthStore.LedgerView(1L, "共享账本", "CNY", "Asia/Shanghai", 10)
        ));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(101))
                .andExpect(jsonPath("$.data.nickname").value("微信用户"))
                .andExpect(jsonPath("$.data.role").value("OWNER"));

        mockMvc.perform(get("/api/v1/ledger")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("共享账本"))
                .andExpect(jsonPath("$.data.maxMembers").value(10));
    }

    @Test
    void missingCurrentUserOrLedgerReturnsNotFound() throws Exception {
        when(authStore.findUserProfile(101L)).thenReturn(Optional.empty());
        when(authStore.findLedger(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/ledger")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void emptyProfilePatchIsRejected() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(authStore, never()).updateUserProfile(
                eq(101L),
                eq(false),
                eq(null),
                eq(false),
                eq(null),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void profilePatchUpdatesOnlyThePresentNickname() throws Exception {
        AuthStore.UserProfileView updated = new AuthStore.UserProfileView(
                101L,
                "家庭成员",
                ORIGINAL_PROFILE.avatarUrl(),
                1L,
                1001L,
                MemberRole.OWNER,
                null
        );
        when(authStore.findUserProfile(101L))
                .thenReturn(Optional.of(ORIGINAL_PROFILE), Optional.of(updated));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  家庭成员  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("家庭成员"))
                .andExpect(jsonPath("$.data.avatarUrl").value(ORIGINAL_PROFILE.avatarUrl()));

        verify(authStore).updateUserProfile(
                eq(101L),
                eq(true),
                eq("家庭成员"),
                eq(false),
                eq(null),
                org.mockito.ArgumentMatchers.any()
        );
        ArgumentCaptor<AuditLogService.AuditEvent> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).append(audit.capture());
        assertThat(audit.getValue().details().get("nicknameChanged")).isEqualTo(true);
        assertThat(audit.getValue().details().get("avatarChanged")).isEqualTo(false);
        assertThat(audit.getValue().details().values()).allSatisfy(value -> assertThat(
                String.valueOf(value)
        ).doesNotContain("家庭成员", ORIGINAL_PROFILE.avatarUrl()));
    }

    @Test
    void profilePutUpdatesNicknameThroughTheSameValidatedContract() throws Exception {
        AuthStore.UserProfileView updated = new AuthStore.UserProfileView(
                101L,
                "家庭成员",
                ORIGINAL_PROFILE.avatarUrl(),
                1L,
                1001L,
                MemberRole.OWNER,
                null
        );
        when(authStore.findUserProfile(101L))
                .thenReturn(Optional.of(ORIGINAL_PROFILE), Optional.of(updated));

        mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  家庭成员  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("家庭成员"))
                .andExpect(jsonPath("$.data.avatarUrl").value(ORIGINAL_PROFILE.avatarUrl()));

        verify(authStore).updateUserProfile(
                eq(101L),
                eq(true),
                eq("家庭成员"),
                eq(false),
                eq(null),
                org.mockito.ArgumentMatchers.any()
        );
        ArgumentCaptor<AuditLogService.AuditEvent> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).append(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo("USER_PROFILE_UPDATE");
        assertThat(audit.getValue().details().get("nicknameChanged")).isEqualTo(true);
        assertThat(audit.getValue().details().get("avatarChanged")).isEqualTo(false);
    }

    @Test
    void explicitNullAvatarClearsOnlyTheAvatar() throws Exception {
        AuthStore.UserProfileView updated = new AuthStore.UserProfileView(
                101L,
                ORIGINAL_PROFILE.nickname(),
                null,
                1L,
                1001L,
                MemberRole.OWNER,
                null
        );
        when(authStore.findUserProfile(101L))
                .thenReturn(Optional.of(ORIGINAL_PROFILE), Optional.of(updated));

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(ORIGINAL_PROFILE.nickname()))
                .andExpect(jsonPath("$.data.avatarUrl").doesNotExist());

        verify(authStore).updateUserProfile(
                eq(101L),
                eq(false),
                eq(null),
                eq(true),
                eq(null),
                org.mockito.ArgumentMatchers.any()
        );
        ArgumentCaptor<AuditLogService.AuditEvent> audit =
                ArgumentCaptor.forClass(AuditLogService.AuditEvent.class);
        verify(auditLogService).append(audit.capture());
        assertThat(audit.getValue().details().get("nicknameChanged")).isEqualTo(false);
        assertThat(audit.getValue().details().get("avatarChanged")).isEqualTo(true);
        assertThat(audit.getValue().details().values()).allSatisfy(value -> assertThat(
                String.valueOf(value)
        ).doesNotContain(ORIGINAL_PROFILE.nickname(), ORIGINAL_PROFILE.avatarUrl()));
    }

    @Test
    void invalidProfileFieldValuesAreRejected() throws Exception {
        assertInvalidPatch("{\"nickname\":null}");
        assertInvalidPatch("{\"nickname\":\"   \"}");
        assertInvalidPatch("{\"nickname\":\"" + "名".repeat(65) + "\"}");
        assertInvalidPatch("{\"avatarUrl\":\"http://example.invalid/a.png\"}");
        assertInvalidPatch("{\"avatarUrl\":\"https://example.invalid/" + "a".repeat(500)
                + "\"}");
    }

    private void assertInvalidPatch(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityTestConfiguration {

        @Bean
        @Primary
        SessionTokenVerifier httpContractSessionTokenVerifier() {
            return token -> VALID_TOKEN.equals(token)
                    ? Optional.of(CURRENT_USER)
                    : Optional.empty();
        }
    }
}
